package net.sprocketgames.lagsense.mixin;

import com.mojang.blaze3d.systems.TimerQuery;
import net.sprocketgames.lagsense.client.ClientFrames;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TimerQuery.class)
abstract class TimerQueryMixin {
    @Shadow @Final private long[] results;
    @Shadow private int currentRotationIndex;
    @Unique private long lagsense$capture;

    @Inject(method = "beginProfile", at = @At("RETURN"))
    private void lagsense$begin(CallbackInfo ci) {
        lagsense$capture = ClientFrames.gpuCapture();
    }

    // Read the completed slot before a new query can reuse it. TimerQuery#get is a rolling mean.
    @Inject(method = "getStatus", at = @At(value = "FIELD",
        target = "Lcom/mojang/blaze3d/systems/TimerQuery;status:Lcom/mojang/blaze3d/systems/TimerQuery$Status;",
        opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void lagsense$complete(CallbackInfoReturnable<TimerQuery.Status> ci) {
        ClientFrames.gpuFrame(lagsense$capture, results[currentRotationIndex]);
    }
}
