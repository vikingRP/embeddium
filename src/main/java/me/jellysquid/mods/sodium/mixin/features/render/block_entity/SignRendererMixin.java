package me.jellysquid.mods.sodium.mixin.features.render.block_entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VikingRP: sign text is drawn up to the sign renderer's view distance (64 blocks), four lines per side, and eight
 * times over for glowing text. Beyond a few dozen blocks it is unreadable, so only the sign model is kept there.
 */
@Mixin(SignRenderer.class)
public class SignRendererMixin {
    @Unique
    private static final double EMBEDDIUM$MAX_TEXT_DISTANCE_SQ = 32.0D * 32.0D;

    @Inject(method = "renderSignText", at = @At("HEAD"), cancellable = true)
    private void embeddium$skipDistantText(BlockPos pos, SignText text, PoseStack poseStack, MultiBufferSource bufferSource,
                                           int light, int lineHeight, int maxLineWidth, boolean isFrontText, CallbackInfo ci) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        if (camera.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > EMBEDDIUM$MAX_TEXT_DISTANCE_SQ) {
            ci.cancel();
        }
    }
}
