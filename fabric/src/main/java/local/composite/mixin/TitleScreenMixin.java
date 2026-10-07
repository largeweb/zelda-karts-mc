package local.composite.mixin;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(TitleScreen.class)
public class TitleScreenMixin {
    @Unique private static boolean composite$opened;
    @Unique private int composite$ticks;
    @Inject(method="tick",at=@At("TAIL"))
    private void composite$open(CallbackInfo ci){
        if(composite$opened || ++composite$ticks<40)return;
        var mc=Minecraft.getInstance();
        if(!Files.isDirectory(mc.gameDirectory.toPath().resolve("saves/Hyrule_World")))return;
        composite$opened=true;
        mc.createWorldOpenFlows().openWorld("Hyrule_World",()->mc.gui.setScreen((TitleScreen)(Object)this));
    }
}
