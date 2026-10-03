package me.jellysquid.mods.sodium.client.gl.arena.staging;

import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import me.jellysquid.mods.sodium.client.gl.buffer.*;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.functions.BufferStorageFunctions;
import me.jellysquid.mods.sodium.client.gl.sync.GlFence;
import me.jellysquid.mods.sodium.client.gl.util.EnumBitField;
import me.jellysquid.mods.sodium.client.util.MathUtil;

import java.nio.ByteBuffer;

public class MappedStagingBuffer implements StagingBuffer {
    private static final EnumBitField<GlBufferStorageFlags> STORAGE_FLAGS =
            EnumBitField.of(GlBufferStorageFlags.PERSISTENT, GlBufferStorageFlags.CLIENT_STORAGE, GlBufferStorageFlags.MAP_WRITE);

    private static final EnumBitField<GlBufferMapFlags> MAP_FLAGS =
            EnumBitField.of(GlBufferMapFlags.PERSISTENT, GlBufferMapFlags.INVALIDATE_BUFFER, GlBufferMapFlags.WRITE, GlBufferMapFlags.EXPLICIT_FLUSH);

    private final FallbackStagingBuffer fallbackStagingBuffer;

    private final MappedBuffer mappedBuffer;
    private final PriorityQueue<CopyCommand> pendingCopies = new ObjectArrayFIFOQueue<>();
    private final PriorityQueue<FencedMemoryRegion> fencedRegions = new ObjectArrayFIFOQueue<>();

    private int start = 0;
    private int pos = 0;

    private final int capacity;
    private int remaining;
    private int pendingBytes;

    public MappedStagingBuffer(CommandList commandList) {
        this(commandList, 1024 * 1024 * 16 /* 16 MB */);
    }

    public MappedStagingBuffer(CommandList commandList, int capacity) {
        GlImmutableBuffer buffer = commandList.createImmutableBuffer(capacity, STORAGE_FLAGS);
        GlBufferMapping map = commandList.mapBuffer(buffer, 0, capacity, MAP_FLAGS);

        this.mappedBuffer = new MappedBuffer(buffer, map);
        this.fallbackStagingBuffer = new FallbackStagingBuffer(commandList);
        this.capacity = capacity;
        this.remaining = this.capacity;
    }

    public static boolean isSupported(RenderDevice instance) {
        return instance.getDeviceFunctions().getBufferStorageFunctions() != BufferStorageFunctions.NONE;
    }

    @Override
    public void enqueueCopy(CommandList commandList, ByteBuffer data, GlBuffer dst, long writeOffset) {
        int length = data.remaining();

        if (length == 0) {
            return;
        }

        if (length > this.remaining) {
            this.fallbackStagingBuffer.enqueueCopy(commandList, data, dst, writeOffset);

            return;
        }

        int remaining = this.capacity - this.pos;

        // Split the transfer in two if we have enough available memory at the end and start of the buffer
        if (length > remaining) {
            int split = length - remaining;

            if (remaining > 0) {
                this.addTransfer(data.slice(data.position(), remaining), dst, this.pos, writeOffset);
            }
            this.addTransfer(data.slice(data.position() + remaining, split), dst, 0, writeOffset + remaining);

            this.pos = split;
        } else {
            this.addTransfer(data, dst, this.pos, writeOffset);
            this.pos += length;
        }

        this.remaining -= length;
        this.pendingBytes += length;
    }

    private void addTransfer(ByteBuffer data, GlBuffer dst, long readOffset, long writeOffset) {
        this.mappedBuffer.map.write(data, (int) readOffset);
        this.pendingCopies.enqueue(new CopyCommand(dst, readOffset, writeOffset, data.remaining()));
    }

    @Override
    public void flush(CommandList commandList) {
        if (this.pendingCopies.isEmpty()) {
            return;
        }

        // Track bytes explicitly: after a full lap pos == start still means the entire ring is dirty.
        int tailBytes = Math.min(this.pendingBytes, this.capacity - this.start);
        if (tailBytes > 0) {
            commandList.flushMappedRange(this.mappedBuffer.map, this.start, tailBytes);
        }
        if (this.pendingBytes > tailBytes) {
            commandList.flushMappedRange(this.mappedBuffer.map, 0, this.pendingBytes - tailBytes);
        }

        this.flushCopies(commandList);
        this.fencedRegions.enqueue(new FencedMemoryRegion(commandList.createFence(), this.pendingBytes));

        this.start = this.pos;
        this.pendingBytes = 0;
    }

    private void flushCopies(CommandList commandList) {
        CopyCommand last = this.pendingCopies.dequeue();

        while (!this.pendingCopies.isEmpty()) {
            CopyCommand command = this.pendingCopies.dequeue();

            if (last.buffer == command.buffer &&
                    last.writeOffset + last.bytes == command.writeOffset &&
                    last.readOffset + last.bytes == command.readOffset) {
                last.bytes += command.bytes;
                continue;
            }

            commandList.copyBufferSubData(this.mappedBuffer.buffer, last.buffer, last.readOffset, last.writeOffset, last.bytes);
            last = command;
        }

        commandList.copyBufferSubData(this.mappedBuffer.buffer, last.buffer, last.readOffset, last.writeOffset, last.bytes);
    }

    @Override
    public void delete(CommandList commandList) {
        this.mappedBuffer.delete(commandList);
        this.fallbackStagingBuffer.delete(commandList);
        this.pendingCopies.clear();
        while (!this.fencedRegions.isEmpty()) {
            this.fencedRegions.dequeue().fence().delete();
        }
    }

    @Override
    public void flip() {
        while (!this.fencedRegions.isEmpty()) {
            var region = this.fencedRegions.first();
            var fence = region.fence();

            if (!fence.isCompleted()) {
                break;
            }

            fence.delete();

            this.fencedRegions.dequeue();
            this.remaining += region.length();
        }
    }

    @Override
    public long getAvailableBytes() {
        return this.remaining;
    }

    private static final class CopyCommand {
        private final GlBuffer buffer;
        private final long readOffset;
        private final long writeOffset;

        private long bytes;

        private CopyCommand(GlBuffer buffer, long readOffset, long writeOffset, long bytes) {
            this.buffer = buffer;
            this.readOffset = readOffset;
            this.writeOffset = writeOffset;
            this.bytes = bytes;
        }
    }

    private record MappedBuffer(GlImmutableBuffer buffer,
                                GlBufferMapping map) {
        public void delete(CommandList commandList) {
            commandList.unmap(this.map);
            commandList.deleteBuffer(this.buffer);
        }
    }

    private record FencedMemoryRegion(GlFence fence, int length) {

    }

    @Override
    public String toString() {
        return "Mapped (%s/%s MiB)".formatted(MathUtil.toMib(this.remaining), MathUtil.toMib(this.capacity));
    }
}
