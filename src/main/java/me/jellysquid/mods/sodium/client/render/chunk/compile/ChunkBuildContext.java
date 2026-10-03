package me.jellysquid.mods.sodium.client.render.chunk.compile;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Collections;

public class ChunkBuildContext {
    public final ChunkBuildBuffers buffers;
    public final BlockRenderCache cache;
    private final ObjectOpenHashSet<TextureAtlasSprite> additionalCapturedSprites;
    private boolean captureAdditionalSprites;

    public ChunkBuildContext(ClientLevel world, ChunkVertexType vertexType) {
        this.buffers = new ChunkBuildBuffers(vertexType);
        this.cache = new BlockRenderCache(Minecraft.getInstance(), world);
        this.additionalCapturedSprites = new ObjectOpenHashSet<>();
    }

    /**
     * Resets per-job state after a build. The native scratch buffers are intentionally kept alive so that the next
     * build on this context does not have to reallocate them; finished meshes are always copied out of them (see
     * {@link ChunkBuildBuffers#createMesh}). Call {@link #destroy()} to release them.
     */
    public void cleanup() {
        this.cache.cleanup();
        this.additionalCapturedSprites.clear();
        this.captureAdditionalSprites = false;
    }

    /**
     * Releases all resources (including native scratch buffers) held by this context. Must only be called from the
     * thread owning the context, once it will no longer be used.
     */
    public void destroy() {
        this.cleanup();
        this.buffers.destroy();
    }

    public void setCaptureAdditionalSprites(boolean flag) {
        captureAdditionalSprites = flag;
        if(!flag) {
            additionalCapturedSprites.clear();
        }
    }

    public Iterable<TextureAtlasSprite> getAdditionalCapturedSprites() {
        return additionalCapturedSprites.isEmpty() ? Collections.emptySet() : additionalCapturedSprites;
    }

    public void captureAdditionalSprite(TextureAtlasSprite sprite) {
        if(captureAdditionalSprites) {
            additionalCapturedSprites.add(sprite);
        }
    }
}
