package me.jellysquid.mods.sodium.mixin.features.render.particle;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * VikingRP: Forge only tests particles against the frustum. Particles hidden behind blocks (campfire smoke, rain
 * splashes, modded effects) are also culled using the chunk visibility graph, like entities.
 */
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    @ModifyExpressionValue(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean embeddium$cullOccludedParticles(boolean isInFrustum, @Local Particle particle) {
        if (!isInFrustum) {
            return false;
        }

        var renderer = SodiumWorldRenderer.instanceNullable();

        return renderer == null || renderer.isParticleVisible(particle.getBoundingBox());
    }
}
