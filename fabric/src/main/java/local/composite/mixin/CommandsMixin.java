package local.composite.mixin;
import com.mojang.brigadier.CommandDispatcher;
import local.composite.ZeldaStatus;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** /link adult and /link child switch Link's age. */
@Mixin(Commands.class)
public class CommandsMixin {
 @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;
 @Inject(method="<init>",at=@At("TAIL"))
 private void composite$commands(CallbackInfo ci){
  dispatcher.register(Commands.literal("link")
   .then(Commands.literal("adult").executes(context->composite$age(context.getSource(),0,"adult")))
   .then(Commands.literal("child").executes(context->composite$age(context.getSource(),1,"child"))));
 }
 @Unique private static int composite$age(CommandSourceStack source,int age,String label){
  ZeldaStatus.requestAge(age);
  source.sendSuccess(()->Component.literal("Link becomes "+label+"."),false);
  return 1;
 }
}
