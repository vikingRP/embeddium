package me.jellysquid.mods.sodium.client.gl.arena.staging;

import me.jellysquid.mods.sodium.client.gl.buffer.GlBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;

import java.nio.ByteBuffer;

public interface StagingBuffer {
    void enqueueCopy(CommandList commandList, ByteBuffer data, GlBuffer dst, long writeOffset);

    void flush(CommandList commandList);

    void delete(CommandList commandList);

    void flip();

    /**
     * {@return the number of bytes that can currently be enqueued without falling back to a slower upload path}
     */
    default long getAvailableBytes() {
        return Long.MAX_VALUE;
    }
}
