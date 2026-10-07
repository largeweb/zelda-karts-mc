package local.composite.mixin;
import local.composite.Passthrough;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public class AvatarCutsceneMixin {
 @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
 private void composite$cinematic(CallbackInfo ci){if(Passthrough.active()&&(!Passthrough.interactive()||Passthrough.aiming()))ci.cancel();}
}
