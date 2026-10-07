package local.composite;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
/** Sequence/acknowledged events; native Zelda collision owns enemy damage. */
public final class NativeCombat {
 record Event(int kind,Vec3 eye,Vec3 direction,float power){}
 private static final ConcurrentLinkedQueue<Event> events=new ConcurrentLinkedQueue<>();
 private static int serial,pending,lastEpoch;private static long lastAttack;
 public static void attack(Minecraft mc){
  if(!Passthrough.interactive()||!NativeBlocks.inDimension()||mc.player==null||mc.gui.screen()!=null)return;
  if(mc.hitResult!=null&&mc.hitResult.getType()==HitResult.Type.BLOCK)return;
  var item=mc.player.getMainHandItem();if(!item.is(ItemTags.SWORDS)&&!item.is(ItemTags.AXES))return;
  long now=System.nanoTime();if(now-lastAttack<500_000_000L)return;lastAttack=now;
  events.add(new Event(item.is(Items.DIAMOND_SWORD)?2:1,mc.player.getEyePosition(),mc.player.getLookAngle(),1));
 }
 public static void projectile(Projectile arrow){
  if(!Passthrough.interactive()||!(arrow instanceof AbstractArrow)||!arrow.level().dimension().equals(NativeBlocks.DIMENSION)||!(arrow.getOwner() instanceof net.minecraft.server.level.ServerPlayer))return;
  if(events.size()>=16)return;
  events.add(new Event(3,arrow.position(),arrow.getDeltaMovement(),1));arrow.discard();
 }
 /** A Minecraft explosion in Hyrule also hurts Zelda actors, as a bomb would. */
 public static void explosion(net.minecraft.world.level.ServerExplosion explosion){
  if(!Passthrough.active()||!explosion.level().dimension().equals(NativeBlocks.DIMENSION)||events.size()>=16)return;
  // Vanilla damages entities out to twice the explosion's power; 40 Zelda units per block.
  events.add(new Event(4,explosion.center(),Vec3.ZERO,explosion.radius()*2*40));
 }
 public static void tick(Shared shm,int epoch){
  if(epoch!=lastEpoch){events.clear();pending=0;lastEpoch=epoch;}
  if(pending!=0&&shm.get(1472)!=pending)return;
  var e=events.poll();if(e==null)return;pending=++serial;
  var pos=e.eye.subtract(Passthrough.origin(),1024,0).scale(40);
  ByteBuffer b=Passthrough.buffer(40);b.putInt(epoch).putInt(pending).putInt(e.kind).putFloat((float)pos.x).putFloat((float)pos.y).putFloat((float)pos.z).putFloat((float)e.direction.x).putFloat((float)e.direction.y).putFloat((float)e.direction.z).putFloat(e.power);shm.publish(1408,b);
 }
}
