package org.embeddedt.embeddium.impl.gametest.tests;

import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockOcclusionCache;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkPreparationChecks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.embeddedt.embeddium.impl.gametest.content.TestRegistry;
import org.embeddedt.embeddium.impl.gametest.content.client.InstrumentingModelWrapper;
import org.embeddedt.embeddium.impl.gametest.util.TestUtils;

public class EmbeddiumGameTests {
    @GameTest(timeoutTicks = 400)
    public static void testChunkSnapshotReuse(GameTestHelper helper) {
        Minecraft.getInstance().submit(ChunkPreparationChecks::checkSnapshotReuse).join();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    public static void testChunkCacheExpiry(GameTestHelper helper) {
        Minecraft.getInstance().submit(ChunkPreparationChecks::checkCacheExpiry).join();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    public static void testChunkResultPublication(GameTestHelper helper) {
        ChunkCompletionChecks.checkPublication();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    public static void testTranslucentSorting(GameTestHelper helper) {
        RenderRegressionChecks.checkSorting();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    public static void testMappedStagingBuffer(GameTestHelper helper) {
        Minecraft.getInstance().submit(RenderRegressionChecks::checkStagingBuffer).join();
        helper.succeed();
    }

    /**
     * Test that the hidesNeighborFace Forge extension is used correctly.
     */
    @GameTest
    public static void testBlockHidingNeighborFace(GameTestHelper helper) {
        BlockOcclusionCache cache = new BlockOcclusionCache();

        BlockPos selfPos = new BlockPos(2, 2, 2);
        BlockState selfState = TestRegistry.TEST_BLOCK.get().defaultBlockState();
        helper.setBlock(selfPos, selfState);
        helper.setBlock(selfPos.relative(Direction.EAST), Blocks.STONE);
        helper.assertTrue(cache.shouldDrawSide(selfState, helper.getLevel(), helper.absolutePos(selfPos), Direction.EAST), "Did not show face of neighbor block as expected");
        helper.setBlock(selfPos.relative(Direction.EAST), selfState);
        helper.assertFalse(cache.shouldDrawSide(selfState, helper.getLevel(), helper.absolutePos(selfPos), Direction.EAST), "Did not hide face of neighbor block as expected");
        helper.succeed();
    }

    /**
     * Test that blocks that mark themselves as isAir but still have a model or other logic are processed by the
     * renderer.
     */
    @GameTest
    public static void testFakeAirBlockRenders(GameTestHelper helper) {
        var fakeAirState = TestRegistry.NOT_AN_AIR_BLOCK.get().defaultBlockState();
        var airBlockModel = (InstrumentingModelWrapper<?>)Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(fakeAirState);
        airBlockModel.resetCalledFlag();
        BlockPos selfPos = new BlockPos(2, 2, 2);
        // Place a non-air block in the same chunk section so that the section is not empty on the client
        helper.setBlock(selfPos.relative(Direction.DOWN), Blocks.OAK_PLANKS.defaultBlockState());
        // Place the fake air block
        helper.setBlock(selfPos, fakeAirState);
        TestUtils.clientBarrier();
        helper.succeedWhen(airBlockModel::hasBeenCalled);
    }
}
