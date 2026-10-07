package local.composite;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.phys.Vec3;
/** Zelda fire reaching Minecraft: fire arrows, Din's Fire and bomb blasts ignite TNT. */
public final class ZeldaFire {
 private static final double SCALE=40;
 public static void tick(Minecraft mc,Shared shm,int epoch){
  var sources=shm.snapshot(928,92);
  var server=mc.getSingleplayerServer();
  if(sources==null||server==null||sources.getInt(0)!=epoch)return;
  var hits=new HashSet<BlockPos>();
  for(int i=0;i<Math.min(3,sources.getInt(4));i++){
   int o=8+i*28;
   Vec3 to=world(sources.getFloat(o),sources.getFloat(o+4),sources.getFloat(o+8)),from=world(sources.getFloat(o+12),sources.getFloat(o+16),sources.getFloat(o+20));
   double radius=sources.getFloat(o+24)/SCALE;
   if(!Double.isFinite(radius)||radius<=0||radius>8||from.distanceTo(to)>16)continue;
   // Sweep from the previous position so a fast arrow cannot pass through a block between ticks.
   int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)*2));
   for(int step=0;step<=steps;step++){
    Vec3 p=from.lerp(to,step/(double)steps);
    for(var pos:BlockPos.betweenClosed(BlockPos.containing(p.x-radius,p.y-radius,p.z-radius),BlockPos.containing(p.x+radius,p.y+radius,p.z+radius)))
     if(mc.level.getBlockState(pos).is(Blocks.TNT)&&Vec3.atCenterOf(pos).distanceTo(p)<=radius+.9)hits.add(pos.immutable());
   }
  }
  if(hits.isEmpty())return;
  server.execute(()->{
   var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
   for(var pos:hits)if(level.getBlockState(pos).is(Blocks.TNT)&&TntBlock.prime(level,pos))level.removeBlock(pos,false);
  });
 }
 private static Vec3 world(float x,float y,float z){return new Vec3(x/SCALE+Passthrough.origin(),1024+y/SCALE,z/SCALE);}
}
