package local.composite;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
/** Blocks live in a persistent empty Minecraft dimension; vanilla owns mining and placement. */
public final class NativeBlocks {
 public static final ResourceKey<Level> DIMENSION=ResourceKey.create(Registries.DIMENSION,Identifier.parse("composite:zelda"));
 static BlockPos target;static int placeCooldown;
 public static boolean inDimension(){var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(DIMENSION);}
 public static void target(java.nio.ByteBuffer p){
  target=p.getInt(32)!=0?BlockPos.containing(p.getFloat(36)/40.0+Passthrough.origin(),1024+Math.ceil(p.getFloat(40)/40.0-0.001),p.getFloat(44)/40.0):null;
  if(placeCooldown>0)placeCooldown--;
 }
 // Only the first block on Zelda ground needs a synthetic surface; subsequent use is vanilla.
 public static boolean useGround(Minecraft mc){
  if(!Passthrough.active()||!inDimension()||mc.player==null||target==null||mc.gui.screen()!=null)return false;
  if(mc.hitResult!=null&&mc.hitResult.getType()!=HitResult.Type.MISS)return false;
  var stack=mc.player.getMainHandItem();if(!(stack.getItem() instanceof BlockItem))return false;
  if(placeCooldown>0)return true;
  BlockPos pos=target.immutable();
  if(!mc.level.getBlockState(pos).canBeReplaced()||Vec3.atCenterOf(pos).distanceTo(mc.player.getEyePosition())>6)return true;
  if(new AABB(pos).intersects(mc.player.getBoundingBox()))return true;
  placeCooldown=4;var uuid=mc.player.getUUID();var server=mc.getSingleplayerServer();
  if(server==null)return true;
  var expected=stack.getItem();
  server.execute(()->{
   var sp=server.getPlayerList().getPlayer(uuid);if(sp==null||!sp.level().dimension().equals(DIMENSION))return;
   if(sp.getMainHandItem().getItem()!=expected||Vec3.atCenterOf(pos).distanceTo(sp.getEyePosition())>6||!sp.level().getBlockState(pos).canBeReplaced())return;
   var hit=new BlockHitResult(new Vec3(pos.getX()+.5,pos.getY(),pos.getZ()+.5),Direction.UP,pos,false);
   expected.useOn(new UseOnContext(sp,InteractionHand.MAIN_HAND,hit));
  });
  mc.player.swing(InteractionHand.MAIN_HAND,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);
  return true;
 }
}
