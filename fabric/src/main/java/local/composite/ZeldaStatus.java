package local.composite;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
/** Health, rupees and magic. Minecraft hearts are the only health; Zelda reports hits. */
public final class ZeldaStatus {
 private static int lastDamage=-1,respawnSerial;
 private static boolean wasDead;
 private static volatile int ageSerial,age;
 /** 0 adult, 1 child. Applied by Zelda the next time Link is free to move. */
 public static void requestAge(int value){age=value;ageSerial++;}
 public static void tick(Minecraft mc,Shared shm,int epoch){
  var status=shm.snapshot(672,32);
  if(status!=null&&status.getInt(0)==epoch){
   int damage=status.getInt(4);
   // Zelda counts 16 per heart, Minecraft 2.
   if(lastDamage>=0&&damage!=lastDamage)hurt(mc,(damage-lastDamage)/8f);
   lastDamage=damage;
   // The experience display carries rupees as the number and magic as the bar.
   int magicMax=status.getInt(16);
   mc.player.experienceLevel=Math.max(0,status.getInt(8));
   mc.player.experienceProgress=magicMax>0?Math.clamp(status.getInt(12)/(float)magicMax,0,1):0;
  }
  boolean dead=mc.player.isDeadOrDying();
  if(wasDead&&!dead)respawnSerial++;
  wasDead=dead;
  ByteBuffer control=Passthrough.buffer(32);
  control.putInt(epoch).putInt(ZeldaItems.held(mc)).putInt(ageSerial).putInt(age).putInt(respawnSerial).putInt(mc.player.getAbilities().instabuild?1:0);
  shm.publish(832,control);
 }
 private static void hurt(Minecraft mc,float amount){
  var server=mc.getSingleplayerServer();if(server==null||amount<=0||amount>40)return;
  var uuid=mc.player.getUUID();
  server.execute(()->{var sp=server.getPlayerList().getPlayer(uuid);if(sp!=null)sp.hurtServer(sp.level(),sp.damageSources().generic(),amount);});
 }
}
