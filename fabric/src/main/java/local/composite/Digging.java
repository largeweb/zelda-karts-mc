package local.composite;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
/**
 * Digging into Zelda's floors.
 *
 * Zelda ground is mined in place: holding attack on it shows Minecraft's cracks on the
 * Zelda surface itself, and when it breaks that column's floor is removed ("revealed")
 * so the hole and the blocks lining it show. The compositor stops drawing Zelda's floor
 * there and Zelda's floor collision is ignored there.
 *
 * Underground is generated lazily: whenever a cell is opened, its buried neighbours are
 * filled with dirt or stone, so a hole is always lined and never opens onto the void.
 * A neighbour counts as buried when it fits entirely under its own column's floor, so
 * lining never pokes out of a slope. When Zelda has no floor information for a column
 * it is assumed solid, because an unlined gap is a fall out of the world.
 */
public final class Digging {
 /** Columns whose Zelda floor has been removed: the floor height (absolute block Y) and its material. */
 private record Column(float height,int material){}
 private static final Map<Long,Column> revealed=new ConcurrentHashMap<>();
 /** Cells that are or were part of a dig; an empty one was dug out and must stay empty. */
 private static final Set<Long> opened=ConcurrentHashMap.newKeySet();
 /** Zelda floor heights per column around the player; NaN where Zelda found no floor. */
 private static final Map<Long,Float> floors=new ConcurrentHashMap<>();
 private static final int MAP=64,WIDE=2048,WIDE_SIDE=25,DEPTH_STONE=4,DEPTH_BEDROCK=24;
 private static final float NONE=-1000,FLUSH=.05f;
 private static Path file;private static boolean loaded,dirty;private static int ticks,wideSerial,lastMode;
 private static final FloatBuffer map=ByteBuffer.allocateDirect(MAP*MAP*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
 private static int mapX=Integer.MIN_VALUE,mapZ;private static boolean mapDirty=true;
 // The Zelda surface cell being mined right now, for the crack overlay.
 private static BlockPos mining;private static float miningHeight,progress;private static int swingTicks;

 private static long column(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
 private static long cell(BlockPos p){return p.asLong();}
 private static int surfaceCell(float height){return (int)Math.ceil(height-.001)-1;}
 public static boolean revealed(int x,int z){return revealed.containsKey(column(x,z));}

 // --- compositor data -------------------------------------------------------------
 public static int mapX(){return mapX;}
 public static int mapZ(){return mapZ;}
 public static boolean any(){return !revealed.isEmpty()||mining!=null;}
 public static BlockPos mining(){return mining;}
 public static float miningHeight(){return miningHeight;}
 /** Vanilla crack stage 0-9 for the surface being mined. */
 public static int miningStage(){return Math.clamp((int)(progress*10),0,9);}
 /** A MAP x MAP grid of removed floor heights around the player, or null when unchanged. */
 public static ByteBuffer mapIfChanged(BlockPos player){
  if(Math.abs(player.getX()-(mapX+MAP/2))>MAP/4||Math.abs(player.getZ()-(mapZ+MAP/2))>MAP/4||mapX==Integer.MIN_VALUE){mapX=player.getX()-MAP/2;mapZ=player.getZ()-MAP/2;mapDirty=true;}
  if(!mapDirty)return null;
  mapDirty=false;
  for(int z=0;z<MAP;z++)for(int x=0;x<MAP;x++){var c=revealed.get(column(mapX+x,mapZ+z));map.put(z*MAP+x,c==null?NONE:c.height());}
  var bytes=ByteBuffer.allocateDirect(MAP*MAP*4).order(ByteOrder.nativeOrder());bytes.asFloatBuffer().put(map.duplicate().clear());return bytes;
 }

 // --- client: per tick ------------------------------------------------------------
 public static void tick(Minecraft mc,Shared shm,int epoch){
  var server=mc.getSingleplayerServer();if(server==null||mc.player==null)return;
  load(server.getWorldPath(LevelResource.ROOT));
  readFloors(shm,epoch);
  // Tell Zelda how to treat the player: on its floor, over a hole, or fully underground.
  var feet=mc.player.blockPosition();long col=column(feet.getX(),feet.getZ());
  var here=revealed.get(col);Float floor=here!=null?Float.valueOf(here.height()):known(floors.get(col));
  int mode=lastMode;float lift=0;
  if(floor!=null){
   double y=mc.player.getY();
   mode=y<floor-1?2:here!=null?1:0;
   // Zelda samples floors from just above the player; underground, lift that to the surface.
   if(y<floor)lift=(float)((floor-y)*40);
  }
  lastMode=mode;
  shm.set(8,mode);shm.f(16,lift);
  mine(mc,shm);
  if(dirty&&++ticks%100==0)save();
 }
 private static Float known(Float height){return height==null||height.isNaN()?null:height;}
 private static void readFloors(Shared shm,int epoch){
  int serial=shm.get(WIDE);if(serial==wideSerial)return;
  var grid=shm.snapshot(WIDE,12+WIDE_SIDE*WIDE_SIDE*4);
  if(grid==null||grid.getInt(0)!=epoch)return;
  wideSerial=serial;
  if(floors.size()>40000)floors.clear();
  int gx=grid.getInt(4)+(int)Passthrough.origin(),gz=grid.getInt(8);
  for(int i=0;i<WIDE_SIDE*WIDE_SIDE;i++){
   float y=grid.getFloat(12+i*4);
   floors.put(column(gx+i%WIDE_SIDE,gz+i/WIDE_SIDE),Float.isFinite(y)&&y>-30000&&y<30000?1024+y/40f:Float.NaN);
  }
 }

 /** Holding attack on bare Zelda ground mines it like a block of that material. */
 private static void mine(Minecraft mc,Shared shm){
  BlockPos target=null;float height=0;int material=0;Block surface=null;
  if(Passthrough.interactive()&&NativeBlocks.inDimension()&&mc.gui.screen()==null&&mc.options.keyAttack.isDown()
     &&!NativeButtons.empty(mc)&&!ZeldaItems.holding(mc)&&(mc.hitResult==null||mc.hitResult.getType()==HitResult.Type.MISS)&&NativeBlocks.target!=null){
   height=NativeBlocks.targetHeight;material=shm.get(12);surface=surfaceBlock(material);
   target=new BlockPos(NativeBlocks.target.getX(),surfaceCell(height),NativeBlocks.target.getZ());
   if(surface==null||revealed(target.getX(),target.getZ())||Vec3.atCenterOf(target).distanceTo(mc.player.getEyePosition())>5.5)target=null;
  }
  if(target==null){mining=null;progress=0;return;}
  if(!target.equals(mining)){mining=target;miningHeight=height;progress=0;}
  var state=surface.defaultBlockState();
  progress+=mc.player.getAbilities().instabuild?1:state.getDestroyProgress(mc.player,mc.level,target);
  if(swingTicks++%4==0)mc.player.swing(InteractionHand.MAIN_HAND,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);
  if(progress<1)return;
  var top=target;float h=height;int m=material;boolean drops=!mc.player.getAbilities().instabuild;
  mining=null;progress=0;
  reveal(top.getX(),top.getZ(),h,m);
  var server=mc.getSingleplayerServer();
  server.execute(()->{
   var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
   // Whatever stood in for the floor here (item support) goes; the cell is now open air.
   if(level.getBlockState(top).is(Blocks.BARRIER))level.setBlock(top,Blocks.AIR.defaultBlockState(),3);
   opened.add(cell(top));dirty=true;
   level.levelEvent(2001,top,Block.getId(state)); // break sound and particles
   if(drops)Block.dropResources(state,level,top);
   line(level,top,h,m);
  });
 }

 private static void reveal(int x,int z,float height,int material){
  if(revealed.putIfAbsent(column(x,z),new Column(height,material))==null){mapDirty=true;dirty=true;}
 }

 // --- server: block changes -------------------------------------------------------
 /** A generated block was removed by anything (mining, TNT, pistons): line the new gap. */
 public static void changed(ServerLevel level,BlockPos pos,BlockState before,BlockState after){
  if(!loaded||!level.dimension().equals(NativeBlocks.DIMENSION)||!after.isAir()||before.isAir()||before.is(Blocks.BARRIER)||!opened.contains(cell(pos)))return;
  var at=pos.immutable();
  level.getServer().execute(()->{
   var source=revealed.get(column(at.getX(),at.getZ()));
   Float floor=source!=null?Float.valueOf(source.height()):known(floors.get(column(at.getX(),at.getZ())));
   line(level,at,floor==null?at.getY()+1:floor,source==null?0:source.material());
  });
 }
 /** Fill the buried neighbours of an opened cell. */
 private static void line(ServerLevel level,BlockPos open,float sourceFloor,int sourceMaterial){
  for(var direction:Direction.values()){
   var pos=open.relative(direction);
   if(opened.contains(cell(pos)))continue;
   var column=revealed.get(column(pos.getX(),pos.getZ()));
   Float measured=floors.get(column(pos.getX(),pos.getZ()));
   // No information, or no Zelda floor there at all: assume solid ground level with this dig.
   float floor=column!=null?column.height():measured==null||measured.isNaN()?sourceFloor:measured;
   if(pos.getY()+1>floor+FLUSH)continue; // would stick out of the ground: leave it to Zelda's surface
   var state=level.getBlockState(pos);
   opened.add(cell(pos));dirty=true;
   if(!state.isAir()&&!state.is(Blocks.BARRIER))continue;
   int material=column!=null?column.material():sourceMaterial,depth=surfaceCell(floor)-pos.getY();
   var block=depth>=DEPTH_BEDROCK?Blocks.BEDROCK:depth>=DEPTH_STONE?Blocks.STONE:subsoil(material);
   level.setBlock(pos,block.defaultBlockState(),3);
  }
 }
 /** Zelda floor material to the block it mines as; null where digging is not allowed. */
 private static Block surfaceBlock(int material){
  return switch(material){
   case 1->Blocks.SAND;
   case 2->Blocks.STONE;
   case 9,10,13->Blocks.OAK_PLANKS;
   case 12->Blocks.PACKED_ICE;
   case 3,4,5,7->null; // water, lava and living floors
   default->Blocks.GRASS_BLOCK;
  };
 }
 private static Block subsoil(int material){
  return switch(material){case 1->Blocks.SANDSTONE;case 2,12->Blocks.STONE;default->Blocks.DIRT;};
 }

 // --- persistence: one small file beside the world --------------------------------
 private static void load(Path world){
  var path=world.resolve("hyrule-digging.dat");
  if(loaded&&path.equals(file))return;
  revealed.clear();opened.clear();floors.clear();file=path;loaded=true;mapDirty=true;dirty=false;
  if(!Files.isRegularFile(path))return;
  try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
   if(in.readInt()!=1)return;
   for(int n=in.readInt();n>0;n--)revealed.put(in.readLong(),new Column(in.readFloat(),in.readInt()));
   for(int n=in.readInt();n>0;n--)opened.add(in.readLong());
  }catch(IOException e){System.err.println("Could not read "+path+": "+e);}
 }
 private static void save(){
  dirty=false;
  try{
   var temp=file.resolveSibling(file.getFileName()+".tmp");
   try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
    out.writeInt(1);
    var columns=new ArrayList<>(revealed.entrySet());out.writeInt(columns.size());
    for(var e:columns){out.writeLong(e.getKey());out.writeFloat(e.getValue().height());out.writeInt(e.getValue().material());}
    var cells=new ArrayList<>(opened);out.writeInt(cells.size());for(long c:cells)out.writeLong(c);
   }
   Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  }catch(IOException e){System.err.println("Could not save "+file+": "+e);}
 }
}
