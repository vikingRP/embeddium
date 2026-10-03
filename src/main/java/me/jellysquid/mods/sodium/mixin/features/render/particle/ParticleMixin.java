package me.jellysquid.mods.sodium.mixin.features.render.particle;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VikingRP: the light of a particle was looked up (block position, chunk check, block state, two light reads) every
 * frame, while its position only changes on ticks. The result is reused while the particle stays in the same block
 * during the same game tick.
 */
@Mixin(Particle.class)
public class ParticleMixin {
    @Shadow
    @Final
    protected ClientLevel level;

    @Shadow
    protected double x;

    @Shadow
    protected double y;

    @Shadow
    protected double z;

    @Unique
    private long embeddium$lightPos = Long.MIN_VALUE;

    @Unique
    private long embeddium$lightTime;

    @Unique
    private int embeddium$light;

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void embeddium$useCachedLight(float partialTick, CallbackInfoReturnable<Integer> cir) {
        if (this.embeddium$lightPos == embeddium$getBlockPosKey() && this.embeddium$lightTime == this.level.getGameTime()) {
            cir.setReturnValue(this.embeddium$light);
        }
    }

    @ModifyReturnValue(method = "getLightColor", at = @At("RETURN"))
    private int embeddium$storeLight(int light) {
        this.embeddium$lightPos = embeddium$getBlockPosKey();
        this.embeddium$lightTime = this.level.getGameTime();
        this.embeddium$light = light;

        return light;
    }

    @Unique
    private long embeddium$getBlockPosKey() {
        return BlockPos.asLong(Mth.floor(this.x), Mth.floor(this.y), Mth.floor(this.z));
    }
}
