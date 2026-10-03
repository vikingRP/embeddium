package me.jellysquid.mods.sodium.client.world.cloned;

import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class ClonedChunkSectionCache {
    private static final int MAX_CACHE_SIZE = 512; /* number of entries */
    private static final long MAX_CACHE_DURATION = TimeUnit.SECONDS.toNanos(5); /* number of nanoseconds */

    private final Level world;
    private final LongSupplier clock;

    private final Long2ReferenceLinkedOpenHashMap<ClonedChunkSection> positionToEntry = new Long2ReferenceLinkedOpenHashMap<>();

    private long time; // updated once per frame to be the elapsed time since application start

    public ClonedChunkSectionCache(Level world) {
        this(world, System::nanoTime);
    }

    ClonedChunkSectionCache(Level world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
        this.time = clock.getAsLong();
    }

    public synchronized void cleanup() {
        this.time = this.clock.getAsLong();
        // Acquires move entries to the tail and stamp them with this monotonic frame time. Expired entries
        // therefore form a prefix; do not scan every live snapshot on every frame.
        while (!this.positionToEntry.isEmpty()) {
            var oldest = this.positionToEntry.get(this.positionToEntry.firstLongKey());
            if (this.time - oldest.getLastUsedTimestamp() <= MAX_CACHE_DURATION) {
                break;
            }
            this.positionToEntry.removeFirst();
        }
    }

    @Nullable
    public synchronized ClonedChunkSection acquire(int x, int y, int z) {
        var pos = SectionPos.asLong(x, y, z);
        var section = this.positionToEntry.getAndMoveToLast(pos);

        if (section == null) {
            section = this.clone(x, y, z);

            while (this.positionToEntry.size() >= MAX_CACHE_SIZE) {
                this.positionToEntry.removeFirst();
            }

            this.positionToEntry.putAndMoveToLast(pos, section);
        }

        section.setLastUsedTimestamp(this.time);

        return section;
    }

    @NotNull
    private ClonedChunkSection clone(int x, int y, int z) {
        LevelChunk chunk = this.world.getChunk(x, z);

        if (chunk == null) {
            throw new RuntimeException("Chunk is not loaded at: " + SectionPos.asLong(x, y, z));
        }

        @Nullable LevelChunkSection section = null;

        if (!this.world.isOutsideBuildHeight(SectionPos.sectionToBlockCoord(y))) {
            section = chunk.getSections()[this.world.getSectionIndexFromSectionY(y)];
        }

        return new ClonedChunkSection(this.world, chunk, section, SectionPos.of(x, y, z));
    }

    public synchronized void invalidate(int x, int y, int z) {
        this.positionToEntry.remove(SectionPos.asLong(x, y, z));
    }
}
