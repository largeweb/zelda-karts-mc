package local.composite.mixin;
import local.composite.Passthrough;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WeatherEffectRenderer.class)
public class WeatherMixin {
 @Inject(method={"render","renderOit"},at=@At("HEAD"),cancellable=true)
 private void composite$weather(CallbackInfo ci){if(Passthrough.active())ci.cancel();}
}
