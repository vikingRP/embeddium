package me.jellysquid.mods.sodium.client.render.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.embeddedt.embeddium.impl.render.chunk.compile.GlobalChunkBuildContext;
import org.jetbrains.annotations.Nullable;

public class SpriteUtil {
    public static void markSpriteActive(@Nullable TextureAtlasSprite sprite) {
        if (sprite == null) {
            // Can happen in some cases, for example if a mod passes a BakedQuad with a null sprite
            // to a VertexConsumer that does not have a texture element.
            return;
        }

        var contents = (SpriteContentsExtended) sprite.contents();

        // The active flag is only read for animated sprites. Skipping the write otherwise (and when already set)
        // avoids dirtying a shared cache line from every thread for every vertex.
        if (!contents.sodium$hasAnimation()) {
            return;
        }

        if (!contents.sodium$isActive()) {
            contents.sodium$setActive(true);
        }

        var context = GlobalChunkBuildContext.get();

        if (context != null) {
            context.captureAdditionalSprite(sprite);
        }
    }

    public static boolean hasAnimation(TextureAtlasSprite sprite) {
        return ((SpriteContentsExtended) sprite.contents()).sodium$hasAnimation();
    }
}
