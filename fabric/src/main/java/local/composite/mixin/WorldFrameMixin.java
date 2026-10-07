package local.composite.mixin;
import local.composite.WorldFrame;
import local.composite.Passthrough;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelRenderer.class)
public class WorldFrameMixin {
 // Keep Minecraft blocks, other entities, hands and GUI; Zelda draws the avatar.
 @Inject(method="lambda$render$0",at=@At("TAIL"))
 private void composite$world(CallbackInfo ci){WorldFrame.draw();}
 @Inject(method="addSkyPass",at=@At("HEAD"),cancellable=true)
 private void composite$sky(CallbackInfo ci){if(Passthrough.active())ci.cancel();}
}
