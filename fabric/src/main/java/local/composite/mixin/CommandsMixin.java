package local.composite.mixin;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import local.composite.Guide;
import local.composite.ZeldaStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** /link adult|child switches Link's age; /help [page] is this mod's guide. */
@Mixin(Commands.class)
public class CommandsMixin {
 @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;
 @Inject(method="<init>",at=@At("TAIL"))
 private void composite$commands(CallbackInfo ci){
  dispatcher.register(Commands.literal("link")
   .then(Commands.literal("adult").executes(context->composite$age(context.getSource(),0,"adult")))
   .then(Commands.literal("child").executes(context->composite$age(context.getSource(),1,"child"))));
  // Minecraft's own /help lists every command; here it is the guide instead.
  composite$forget(dispatcher.getRoot(),"help");
  dispatcher.register(Commands.literal("help")
   .executes(context->composite$help(context.getSource(),1))
   .then(Commands.argument("page",IntegerArgumentType.integer(1,Guide.PAGES.size()))
    .executes(context->composite$help(context.getSource(),IntegerArgumentType.getInteger(context,"page")))));
 }
 /** Brigadier has no way to remove a command, so take it out of the node's tables. */
 @Unique private static void composite$forget(CommandNode<?> root,String name){
  try{
   for(var table:new String[]{"children","literals","arguments"}){
    var field=CommandNode.class.getDeclaredField(table);field.setAccessible(true);
    ((java.util.Map<?,?>)field.get(root)).remove(name);
   }
  }catch(ReflectiveOperationException e){System.err.println("Could not replace /"+name+": "+e);}
 }
 @Unique private static int composite$help(CommandSourceStack source,int number){
  var page=Guide.PAGES.get(number-1);
  source.sendSuccess(()->Component.literal("— "+page.title()+" ("+number+"/"+Guide.PAGES.size()+") —").withStyle(ChatFormatting.GOLD),false);
  for(var line:page.lines())source.sendSuccess(()->Component.literal(line),false);
  source.sendSuccess(()->Component.literal(number<Guide.PAGES.size()?"/help "+(number+1)+" for "+Guide.PAGES.get(number).title():"/help 1 to start again").withStyle(ChatFormatting.GRAY),false);
  return 1;
 }
 @Unique private static int composite$age(CommandSourceStack source,int age,String label){
  ZeldaStatus.requestAge(age);
  source.sendSuccess(()->Component.literal("Link becomes "+label+"."),false);
  return 1;
 }
}
