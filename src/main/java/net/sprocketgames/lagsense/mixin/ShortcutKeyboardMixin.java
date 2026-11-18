package net.sprocketgames.lagsense.mixin;
import net.sprocketgames.lagsense.client.ShortcutBindings;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.*;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Vanilla fullscreen/screenshot checks run before normal modified keybinding dispatch. */
@Mixin(KeyboardHandler.class)
abstract class ShortcutKeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"))
    private void lagsense$press(long window,int action,KeyEvent event,CallbackInfo callback){ShortcutBindings.key(window,action,event);}
    @WrapOperation(method="keyPress",at=@At(value="INVOKE",target="Lnet/minecraft/client/Minecraft;handleGlobalKeyPress(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)Z"))
    private boolean lagsense$globalKey(Minecraft mc,InputConstants.Key key,boolean controlDown,Operation<Boolean> original){
        if ((mc.options.keyFullscreen.matches(key)||mc.options.keyScreenshot.matches(key))&&ShortcutBindings.claimsModified(key)) return false;
        return original.call(mc,key,controlDown);
    }
}
