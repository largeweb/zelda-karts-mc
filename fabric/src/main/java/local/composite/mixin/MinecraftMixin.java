package local.composite.mixin;
import local.composite.Bridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method="updateTitle",at=@At("TAIL"))
    private void composite$title(CallbackInfo ci){
        if(local.composite.Passthrough.ENABLED)org.lwjgl.sdl.SDLVideo.SDL_SetWindowTitle(((Minecraft)(Object)this).getWindow().handle(),"Minecraft x Ocarina of Time");
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void composite$tick(CallbackInfo ci){Bridge.tick((Minecraft)(Object)this);local.composite.Dev.tick((Minecraft)(Object)this);local.composite.Lifecycle.tick((Minecraft)(Object)this);}
}
