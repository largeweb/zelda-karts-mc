package local.composite.mixin;
import local.composite.Passthrough;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(CloudRenderer.class)
public class CloudMixin {
 @Inject(method={"render","renderOit"},at=@At("HEAD"),cancellable=true)
 private void composite$clouds(CallbackInfo ci){if(Passthrough.active())ci.cancel();}
}
