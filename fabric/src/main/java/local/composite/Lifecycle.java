package local.composite;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
/** The launcher asks for a clean save-and-quit by creating <shm>.quit; signals can hang the JVM mid-save. */
public final class Lifecycle {
 private static final Path QUIT=Path.of(System.getProperty("composite.shm","/dev/shm/hyrule-composite")+".quit");
 private static int ticks;
 public static void tick(Minecraft mc){
  if(++ticks%10!=0||!Files.exists(QUIT))return;
  try{Files.delete(QUIT);}catch(Exception e){return;}
  mc.stop();
 }
}
