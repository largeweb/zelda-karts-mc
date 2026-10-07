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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
/**
 * Digging into Zelda's floors.
 *
 * Hitting Zelda ground turns that spot into a real Minecraft block ("revealing" its
 * column): the compositor stops drawing Zelda's floor there and Zelda's floor collision
 * is ignored there, so the block and whatever is dug beneath it take over. Underground
 * is generated lazily: whenever a generated block is removed, its still-buried
 * neighbours are filled in, so a hole is always lined with blocks and never opens onto
 * the void. Walls are left alone; only floors can be revealed.
 */
public final class Digging {
 /** Columns whose Zelda floor has been replaced, with the floor height (absolute block Y) and surface material. */
 private record Column(float height,int material){}
 private static final Map<Long,Column> revealed=new ConcurrentHashMap<>();
 /** Cells that have been generated at some point; an empty one was dug out and must stay empty. */
 private static final Set<Long> opened=ConcurrentHashMap.newKeySet();
 /** Last known Zelda floor height per column near the player, for deciding what counts as buried. */
 private static final Map<Long,Float> floors=new ConcurrentHashMap<>();
 private static final int MAP=64,DEPTH_STONE=4,DEPTH_BEDROCK=24;
 private static final float NONE=-1000;
 private static Path file;private static boolean loaded,dirty;private static int ticks,lastMaterial;
 private static final FloatBuffer map=ByteBuffer.allocateDirect(MAP*MAP*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
 private static int mapX=Integer.MIN_VALUE,mapZ;private static boolean mapDirty=true;

 private static long column(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
 private static long cell(BlockPos p){return p.asLong();}
 private static int surfaceCell(float height){return (int)Math.ceil(height-.001)-1;}
 public static boolean revealed(int x,int z){return revealed.containsKey(column(x,z));}

 // --- compositor data -------------------------------------------------------------
 public static int mapX(){return mapX;}
 public static int mapZ(){return mapZ;}
 public static boolean any(){return !revealed.isEmpty();}
 /** A MAP x MAP grid of revealed floor heights around the player, or null when unchanged. */
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
  // Remember Zelda's floor heights from the grid it publishes around the player.
  var grid=shm.snapshot(1536,336);
  if(grid!=null&&grid.getInt(0)==epoch){
   int gx=grid.getInt(4)+(int)Passthrough.origin(),gz=grid.getInt(8);
   for(int i=0;i<81;i++){float y=grid.getFloat(12+i*4);if(Float.isFinite(y)&&y>-30000&&y<30000)floors.put(column(gx+i%9,gz+i/9),1024+y/40f);}
   if(floors.size()>20000)floors.clear();
  }
  // Tell Zelda how to treat the player: on its floor, over a hole, or fully underground.
  var feet=mc.player.blockPosition();long col=column(feet.getX(),feet.getZ());
  var here=revealed.get(col);Float floor=here!=null?Float.valueOf(here.height()):floors.get(col);
  int mode=0;float lift=0;
  if(floor!=null){
   double y=mc.player.getY();
   if(y<floor-1)mode=2;else if(here!=null)mode=1;
   // Zelda samples floors from just above the player; underground, lift that to the surface.
   if(y<floor)lift=(float)((floor-y)*40);
  }else mode=lastMode;
  lastMode=mode;
  shm.set(8,mode);shm.f(16,lift);
  if(dirty&&++ticks%100==0)save();
 }
 private static int lastMode;

 /** Attack on bare Zelda ground: reveal that column and put its surface block there. */
 public static void start(Minecraft mc,Shared shm){
  if(!Passthrough.interactive()||!NativeBlocks.inDimension()||mc.player==null||mc.gui.screen()!=null)return;
  if(NativeButtons.empty(mc)||ZeldaItems.holding(mc))return;
  if(mc.hitResult!=null&&mc.hitResult.getType()!=net.minecraft.world.phys.HitResult.Type.MISS)return;
  var above=NativeBlocks.target;if(above==null||shm==null)return;
  float height=NativeBlocks.targetHeight;int material=shm.get(12);
  var top=new BlockPos(above.getX(),surfaceCell(height),above.getZ());
  if(net.minecraft.world.phys.Vec3.atCenterOf(top).distanceTo(mc.player.getEyePosition())>5.5||surfaceBlock(material)==null)return;
  var server=mc.getSingleplayerServer();if(server==null)return;
  lastMaterial=material;
  reveal(top.getX(),top.getZ(),height,material);
  server.execute(()->{var level=server.getLevel(NativeBlocks.DIMENSION);if(level!=null)generate(level,top);});
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
   // Breaking a column's surface block from below or the side opens that column from above too.
   Float floor=height(at.getX(),at.getZ());
   if(floor!=null&&at.getY()==surfaceCell(floor))reveal(at.getX(),at.getZ(),floor,lastMaterial);
   for(var direction:Direction.values())generate(level,at.relative(direction));
  });
 }
 private static Float height(int x,int z){var c=revealed.get(column(x,z));return c!=null?Float.valueOf(c.height()):floors.get(column(x,z));}
 private static void generate(ServerLevel level,BlockPos pos){
  Float floor=height(pos.getX(),pos.getZ());
  if(floor==null||pos.getY()+.5>=floor||!opened.add(cell(pos)))return;
  dirty=true;
  var state=level.getBlockState(pos);
  if(!state.isAir()&&!state.is(Blocks.BARRIER))return;
  var column=revealed.get(column(pos.getX(),pos.getZ()));
  int material=column!=null?column.material():lastMaterial,depth=surfaceCell(floor)-pos.getY();
  var block=depth>=DEPTH_BEDROCK?Blocks.BEDROCK:depth>=DEPTH_STONE?Blocks.STONE:depth>0?subsoil(material):surfaceBlock(material);
  level.setBlock(pos,(block==null?Blocks.DIRT:block).defaultBlockState(),3);
 }
 /** Zelda floor material to the block its surface becomes; null where digging is not allowed. */
 private static net.minecraft.world.level.block.Block surfaceBlock(int material){
  return switch(material){
   case 1->Blocks.SAND;
   case 2->Blocks.STONE;
   case 9,10,13->Blocks.OAK_PLANKS;
   case 12->Blocks.PACKED_ICE;
   case 3,4,5,7->null; // water, lava and living floors
   default->Blocks.GRASS_BLOCK;
  };
 }
 private static net.minecraft.world.level.block.Block subsoil(int material){
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
