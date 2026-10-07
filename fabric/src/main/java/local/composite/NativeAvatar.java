package local.composite;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
/** Link's body size comes from Zelda (adult or child); Minecraft owns controls. */
public final class NativeAvatar {
 private static volatile UUID owner;
 private static Entity attachedPlayer;
 private static volatile float radius=14,height=40,eye=34;
 private static int epoch;
 public static boolean owns(Entity entity){return Passthrough.active()&&entity instanceof Player&&entity.getUUID().equals(owner);}
 public static EntityDimensions dimensions(Entity entity,Pose pose){
  if(!owns(entity))return null;
  float h=height,e=eye;
  if(pose==Pose.CROUCHING){h*=.8f;e*=.8f;}
  if(pose==Pose.SWIMMING||pose==Pose.FALL_FLYING||pose==Pose.SPIN_ATTACK){h=Math.min(h,24);e=h*.8f;}
  return EntityDimensions.scalable(Math.min(.6f,radius*2/40),h/40).withEyeHeight(e/40);
 }
 public static void tick(Minecraft mc,Shared shm,int currentEpoch,java.nio.ByteBuffer story){
  boolean newPlayer=attachedPlayer!=mc.player||epoch!=currentEpoch;attachedPlayer=mc.player;epoch=currentEpoch;owner=mc.player.getUUID();
  var avatar=shm.snapshot(640,28);
  if(avatar!=null&&avatar.getInt(0)==epoch){
   float r=avatar.getFloat(8),h=avatar.getFloat(12),e=avatar.getFloat(16);
   if(Float.isFinite(r)&&Float.isFinite(h)&&Float.isFinite(e)&&r>=5&&r<=40&&h>=12&&h<=120&&e>0&&e<=h){
    if(newPlayer||radius!=r||height!=h||eye!=e){radius=r;height=h;eye=e;mc.player.refreshDimensions();var uuid=owner;var server=mc.getSingleplayerServer();
     server.execute(()->{var sp=server.getPlayerList().getPlayer(uuid);if(sp!=null)sp.refreshDimensions();});}
   }
  }
 }
}
