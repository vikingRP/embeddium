package me.jellysquid.mods.sodium.client.world.cloned;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.biome.BiomeColorCache;
import me.jellysquid.mods.sodium.client.world.biome.BiomeColorSource;
import me.jellysquid.mods.sodium.client.world.biome.BiomeSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPreparationChecks {
    public static void checkSnapshotReuse() {
        var world = Minecraft.getInstance().level;
        WorldSlice slice = new WorldSlice(world);
        var registry = world.registryAccess().registryOrThrow(Registries.BIOME);
        var filled = new LevelChunkSection(registry);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    filled.setBlockState(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        filled.fillBiomesFromNoise((x, y, z, sampler) -> registry.getHolderOrThrow(
                (x & 1) == 0 ? Biomes.SWAMP : Biomes.PLAINS), null, 0, 0, 0);

        // Reuse the same worker storage through air -> solid -> air, with resets and skipped biome requests.
        for (int iteration = 0; iteration < 6; iteration++) {
            var origin = SectionPos.of(iteration % 2 == 0 ? 0 : -1, 0, 0);
            boolean hasBlocks = iteration % 3 == 1;
            var context = context(origin, hasBlocks ? filled : null);
            slice.copyData(context);
            var bounds = context.getVolume();
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                        require(slice.getBlockState(x, y, z) == (hasBlocks
                                ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState()),
                                "Stale block data after snapshot reuse");
                    }
                }
            }

            if (iteration != 0) {
                // Compare lazy reads with the original eager biome/color preparation path.
                BiomeSlice eagerBiomes = new BiomeSlice();
                eagerBiomes.update(world, context);
                BiomeColorCache eagerColors = new BiomeColorCache(eagerBiomes,
                        Minecraft.getInstance().options.biomeBlendRadius().get());
                eagerColors.update(context);
                // Vary the first entry point to exercise all three public lazy accessors.
                BlockPos first = origin.origin();
                if (iteration % 3 == 0) slice.getBiomeFabric(first);
                if (iteration % 3 == 1) slice.getColor(BiomeColorSource.GRASS, first.getX(), first.getY(), first.getZ());
                if (iteration % 3 == 2) slice.getBlockTint(first, BiomeColors.WATER_COLOR_RESOLVER);
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 3) {
                    for (int x = bounds.minX(); x <= bounds.maxX(); x += 3) {
                        BlockPos pos = new BlockPos(x, 3, z);
                        require(slice.getBiomeFabric(pos) == eagerBiomes.getBiome(x, 3, z), "Biome mismatch");
                        for (BiomeColorSource source : BiomeColorSource.VALUES) {
                            require(slice.getColor(source, x, 3, z) == eagerColors.getColor(source, x, 3, z),
                                    "Biome color mismatch for " + source);
                        }
                        require(slice.getBlockTint(pos, BiomeColors.WATER_COLOR_RESOLVER)
                                == eagerColors.getColor(BiomeColors.WATER_COLOR_RESOLVER, x, 3, z),
                                "Block tint mismatch");
                    }
                }
            }
            slice.reset();
        }
    }

    private static ChunkRenderContext context(SectionPos origin, LevelChunkSection section) {
        var world = Minecraft.getInstance().level;
        ClonedChunkSection[] sections = new ClonedChunkSection[27];
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    var pos = SectionPos.of(origin.getX() + x - 1, origin.getY() + y - 1, origin.getZ() + z - 1);
                    sections[WorldSlice.getLocalSectionIndex(x, y, z)] = new ClonedChunkSection(world,
                            world.getChunk(pos.getX(), pos.getZ()), section, pos);
                }
            }
        }
        return new ChunkRenderContext(origin, sections, new BoundingBox(
                origin.minBlockX() - 2, origin.minBlockY() - 2, origin.minBlockZ() - 2,
                origin.maxBlockX() + 2, origin.maxBlockY() + 2, origin.maxBlockZ() + 2));
    }

    public static void checkCacheExpiry() {
        AtomicLong time = new AtomicLong();
        var cache = new ClonedChunkSectionCache(Minecraft.getInstance().level, time::get);
        var oldest = cache.acquire(0, 0, 0);
        var reused = cache.acquire(0, 1, 0);
        time.set(TimeUnit.SECONDS.toNanos(4));
        cache.cleanup();
        require(cache.acquire(0, 1, 0) == reused, "Live snapshot was discarded");
        var newest = cache.acquire(0, 2, 0);
        time.set(TimeUnit.SECONDS.toNanos(6));
        cache.cleanup();
        require(cache.acquire(0, 0, 0) != oldest, "Expired oldest snapshot was retained");
        require(cache.acquire(0, 1, 0) == reused, "Recently reused snapshot expired too early");
        require(cache.acquire(0, 2, 0) == newest, "Cleanup discarded a live snapshot");
        cache.invalidate(0, 1, 0);
        require(cache.acquire(0, 1, 0) != reused, "Invalidation retained stale data");
        time.set(TimeUnit.SECONDS.toNanos(12));
        cache.cleanup();
        require(cache.acquire(0, 2, 0) != newest, "Cleanup did not expire the remaining entries");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
