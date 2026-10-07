package local.composite;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
/** Invisible, coarse support for vanilla drops/gravity blocks on sampled Zelda ground.
 * Both the local player and its integrated-server counterpart ignore these proxies.
 * The player uses the exact Zelda collision query; items still use the proxies.
 */
public final class FloorSupport {
 private static int lastSerial;private static final Map<Long,BlockPos> heights=new HashMap<>();
 public static void tick(Minecraft mc,Shared shared,int epoch){
  int serial=shared.get(1536);if(serial==lastSerial||mc.getSingleplayerServer()==null||!NativeBlocks.inDimension())return;
  var b=shared.snapshot(1536,336);if(b==null||b.getInt(0)!=epoch)return;lastSerial=serial;
  int x=b.getInt(4),z=b.getInt(8);double origin=Passthrough.origin();List<BlockPos> support=new ArrayList<>();
  for(int i=0;i<81;i++){float y=b.getFloat(12+i*4);if(Float.isFinite(y)&&y>-30000&&y<30000)support.add(new BlockPos((int)origin+x+i%9,1024+(int)Math.ceil(y/40.0-.001)-1,z+i/9));}
  var server=mc.getSingleplayerServer();server.execute(()->{
   var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
   for(var pos:support){
    long key=((long)pos.getX()<<32)^(pos.getZ()&0xffffffffL);var old=heights.put(key,pos);
    if(old!=null&&!old.equals(pos)&&level.getBlockState(old).is(Blocks.BARRIER))level.setBlock(old,Blocks.AIR.defaultBlockState(),3);
    if(level.getBlockState(pos).isAir())level.setBlock(pos,Blocks.BARRIER.defaultBlockState(),3);
   }
  });
 }
 public static void hidePick(Minecraft mc){
  if(!Passthrough.active()||!NativeBlocks.inDimension())return;
  if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult h&&mc.level.getBlockState(h.getBlockPos()).is(Blocks.BARRIER))
   mc.hitResult=net.minecraft.world.phys.BlockHitResult.miss(h.getLocation(),h.getDirection(),h.getBlockPos());
 }
}
