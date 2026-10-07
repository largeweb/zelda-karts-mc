package local.composite;
import net.minecraft.client.Minecraft;
/** Empty hands delegate to the native form; held items retain Minecraft behavior. */
public final class NativeButtons {
 private static int pending;
 public static boolean empty(Minecraft mc){return Passthrough.active()&&mc.player!=null&&mc.gui.screen()==null&&mc.player.getMainHandItem().isEmpty()&&mc.player.getOffhandItem().isEmpty();}
 public static boolean click(Minecraft mc,boolean attack){if(!empty(mc))return false;pending|=1<<(attack?20:19);return true;}
 public static int poll(Minecraft mc){
  int result=pending;pending=0;if(!empty(mc))return 0;
  if(mc.options.keyAttack.isDown())result|=1<<20;
  if(mc.options.keyUse.isDown())result|=1<<19;
  return result;
 }
}
