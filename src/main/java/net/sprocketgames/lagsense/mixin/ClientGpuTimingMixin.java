package net.sprocketgames.lagsense.mixin;

import net.sprocketgames.lagsense.client.ClientFrames;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Reuses Minecraft's query lifecycle; never starts a competing query or waits for a result. */
@Mixin(Minecraft.class)
abstract class ClientGpuTimingMixin {
    @ModifyExpressionValue(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntryList;isCurrentlyEnabled(Lnet/minecraft/resources/Identifier;)Z"))
    private boolean lagsense$gpuRequested(boolean original) { return original || ClientFrames.wantsGpu(); }
}
