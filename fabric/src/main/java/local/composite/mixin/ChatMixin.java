package local.composite.mixin;
import local.composite.Karts;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Lets a command ask a question and take the next chat line as its answer. */
@Mixin(ClientPacketListener.class)
public class ChatMixin {
 @Inject(method="sendChat",at=@At("HEAD"),cancellable=true)
 private void composite$answer(String text,CallbackInfo ci){if(Karts.answer(text))ci.cancel();}
 /** On a server, /link, /spawnkart and /guide are this mod's own and never reach the server. */
 @Inject(method="sendCommand",at=@At("HEAD"),cancellable=true)
 private void composite$command(String command,CallbackInfo ci){if(local.composite.Remote.ownCommand(command))ci.cancel();}
}
