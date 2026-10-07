package local.composite;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
/**
 * Digging into Zelda's scenery, floors and walls alike.
 *
 * The world is treated as block cells. Mining Zelda scenery "carves" the cell just
 * behind the surface the crosshair is on: the compositor stops drawing Zelda's scenery
 * inside carved cells and Zelda's collision is switched off around them, so the space
 * behaves like dug-out Minecraft. Cracks are drawn on the Zelda surface while mining.
 *
 * What lies behind the surface is generated lazily. When a cell is carved, Zelda is
 * asked about each neighbour: one that is solid (no scenery through it, no floor under
 * it) is filled with soil, then stone further in, so dug space is always lined with
 * blocks and never opens onto the void. Breaking one of those blocks carves its cell
 * in turn, taking any Zelda surface lying on it along with it.
 */
public final class Digging {
 // Per cell: how many cells in from the original surface, the surface material, and
 // whether it has been dug out (carved) or still holds the block generated for it.
 private static final int DEPTH=0xff,MATERIAL_SHIFT=8,CARVED=1<<16;
 private static final Map<Long,Integer> cells=new ConcurrentHashMap<>();
 private static final int MAP=64,LAYERS=24,DEPTH_STONE=4,DEPTH_BEDROCK=24;
 private static final int HIT=4608,CLASSIFY_REQUEST=4672,CLASSIFY_REPLY=4928,CLASSIFY_MAX=16;
 private static final int NO_FLOOR=1,NO_WALLS=2,NO_CEILING=4,SOLID=1;
 private static Path file;private static boolean loaded,dirty;private static int ticks;
 private static int mapX=Integer.MIN_VALUE,mapY,mapZ;private static volatile boolean mapDirty=true;
 // The Zelda surface cell being mined right now, for the crack overlay.
 private static BlockPos mining;private static float progress;private static int stage,swingTicks;
 // Neighbours waiting for Zelda to say whether they are solid.
 private record Pending(BlockPos pos,int depth,int material){}
 private static final ConcurrentLinkedQueue<Pending> queue=new ConcurrentLinkedQueue<>();
 private static List<Pending> asked=List.of();private static int serial,askedTicks;

 private static boolean carved(int x,int y,int z){Integer v=cells.get(BlockPos.asLong(x,y,z));return v!=null&&(v&CARVED)!=0;}
 public static boolean carved(BlockPos p){return carved(p.getX(),p.getY(),p.getZ());}

 // --- compositor data -------------------------------------------------------------
 public static int mapX(){return mapX;}
 public static int mapY(){return mapY;}
 public static int mapZ(){return mapZ;}
 public static boolean any(){return !cells.isEmpty()||mining!=null;}
 public static BlockPos mining(){return mining;}
 /** Vanilla crack stage 0-9 for the surface being mined. */
 public static int miningStage(){return Math.clamp(stage,0,9);}
 /**
  * Carved cells around the player for the shader, or null when unchanged: one value per
  * block column holding a bit per layer (whole numbers below 2^24 are exact in a float).
  */
 public static ByteBuffer mapIfChanged(BlockPos player){
  if(mapX==Integer.MIN_VALUE||Math.abs(player.getX()-(mapX+MAP/2))>MAP/4||Math.abs(player.getZ()-(mapZ+MAP/2))>MAP/4||Math.abs(player.getY()-(mapY+LAYERS/2))>LAYERS/4){
   mapX=player.getX()-MAP/2;mapZ=player.getZ()-MAP/2;mapY=player.getY()-LAYERS/2;mapDirty=true;
  }
  if(!mapDirty)return null;
  mapDirty=false;
  int[] bits=new int[MAP*MAP];
  for(var e:cells.entrySet()){
   if((e.getValue()&CARVED)==0)continue;
   long key=e.getKey();int x=BlockPos.getX(key)-mapX,y=BlockPos.getY(key)-mapY,z=BlockPos.getZ(key)-mapZ;
   if(x>=0&&x<MAP&&z>=0&&z<MAP&&y>=0&&y<LAYERS)bits[z*MAP+x]|=1<<y;
  }
  var bytes=ByteBuffer.allocateDirect(MAP*MAP*4).order(ByteOrder.nativeOrder());
  for(int b:bits)bytes.putFloat(b);
  return bytes.flip();
 }

 // --- client: per tick ------------------------------------------------------------
 public static void tick(Minecraft mc,Shared shm,int epoch){
  var server=mc.getSingleplayerServer();if(server==null||mc.player==null)return;
  load(server.getWorldPath(LevelResource.ROOT));
  shm.set(8,collisionMode(mc));
  mine(mc,shm,epoch);
  classify(mc,shm,epoch);
  if(dirty&&++ticks%100==0)save();
 }

 /** Which of Zelda's collision to ignore: dug-out space is bounded by Minecraft blocks instead. */
 private static int collisionMode(Minecraft mc){
  if(cells.isEmpty())return 0;
  var player=mc.player;var box=player.getBoundingBox();
  int x=(int)Math.floor(player.getX()),z=(int)Math.floor(player.getZ());
  int mode=0;
  // Standing in or over a dug cell: Zelda's floor there is gone.
  if(carved(x,(int)Math.floor(box.minY-.05),z)||carved(x,(int)Math.floor(box.minY+.01),z))mode|=NO_FLOOR;
  if(carved(x,(int)Math.floor(box.maxY+.2),z))mode|=NO_CEILING;
  // Zelda keeps the player a body-width from its walls, so its walls must give way
  // before the player reaches a hole in one.
  var reach=box.inflate(.5,0,.5);
  walls:for(int cx=(int)Math.floor(reach.minX);cx<=(int)Math.floor(reach.maxX);cx++)
   for(int cz=(int)Math.floor(reach.minZ);cz<=(int)Math.floor(reach.maxZ);cz++)
    for(int cy=(int)Math.floor(box.minY+.01);cy<=(int)Math.floor(box.maxY-.01);cy++)
     if(carved(cx,cy,cz)){mode|=NO_WALLS;break walls;}
  return mode;
 }

 /** Holding attack on Zelda scenery mines it like a block of that material. */
 private static void mine(Minecraft mc,Shared shm,int epoch){
  // Vanilla mining of a real block: its cracks are drawn on any Zelda surface lying on it too.
  if(mc.gameMode.isDestroying()&&mc.hitResult instanceof BlockHitResult block&&block.getType()==HitResult.Type.BLOCK){
   mining=block.getBlockPos().immutable();stage=mc.gameMode.getDestroyStage();progress=0;return;
  }
  BlockPos target=null;int material=0;Block surface=null;
  var hit=shm.snapshot(HIT,36);
  if(hit!=null&&hit.getInt(0)==epoch&&hit.getInt(4)!=0&&Passthrough.interactive()&&NativeBlocks.inDimension()&&mc.gui.screen()==null&&mc.options.keyAttack.isDown()
     &&!NativeButtons.empty(mc)&&!ZeldaItems.holding(mc)&&(mc.hitResult==null||mc.hitResult.getType()==HitResult.Type.MISS)){
   var point=new Vec3(hit.getFloat(8)/40.0+Passthrough.origin(),1024+hit.getFloat(12)/40.0,hit.getFloat(16)/40.0);
   var eye=mc.player.getEyePosition();
   // The cell just behind the surface, along the line of sight.
   target=BlockPos.containing(point.add(point.subtract(eye).normalize().scale(.05)));
   material=hit.getInt(32);surface=surfaceBlock(material);
   if(surface==null||carved(target)||point.distanceTo(eye)>(mc.player.getAbilities().instabuild?5:4.5))target=null;
  }
  if(target==null){mining=null;progress=0;return;}
  if(!target.equals(mining)){mining=target;progress=0;}
  var state=surface.defaultBlockState();
  progress+=mc.player.getAbilities().instabuild?1:state.getDestroyProgress(mc.player,mc.level,target);
  stage=(int)(progress*10);
  if(swingTicks++%4==0)mc.player.swing(InteractionHand.MAIN_HAND,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);
  if(progress<1)return;
  var pos=target;int m=material;boolean drops=!mc.player.getAbilities().instabuild;
  mining=null;progress=0;
  var server=mc.getSingleplayerServer();
  server.execute(()->{
   var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
   var existing=level.getBlockState(pos);
   carve(pos,0,m);
   // A block already lying under this surface goes with it, in one break.
   boolean real=!existing.isAir()&&!existing.is(Blocks.BARRIER);
   if(!existing.isAir())level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
   level.levelEvent(2001,pos,Block.getId(real?existing:state)); // break sound and particles
   if(drops)Block.dropResources(real?existing:state,level,pos);
  });
 }

 /** Mark a cell dug out and ask Zelda about its neighbours. */
 private static void carve(BlockPos pos,int depth,int material){
  Integer old=cells.get(pos.asLong());
  if(old!=null){depth=old&DEPTH;material=(old>>MATERIAL_SHIFT)&0xff;}
  cells.put(pos.asLong(),depth|material<<MATERIAL_SHIFT|CARVED);
  mapDirty=true;dirty=true;
  for(var direction:Direction.values()){
   var next=pos.relative(direction);
   if(!cells.containsKey(next.asLong()))queue.add(new Pending(next.immutable(),Math.min(depth+1,DEPTH),material));
  }
 }

 /** One batch of neighbour cells at a time goes to Zelda; solid ones are filled in. */
 private static void classify(Minecraft mc,Shared shm,int epoch){
  if(!asked.isEmpty()){
   if(shm.get(CLASSIFY_REPLY)!=serial){
    // Zelda answers within a frame or two; if it cannot (scene change), ask again later.
    if(++askedTicks>40){queue.addAll(asked);asked=List.of();}
    return;
   }
   var answered=asked;asked=List.of();
   var fill=new ArrayList<Pending>();
   for(int i=0;i<answered.size();i++)if(shm.mem.get(CLASSIFY_REPLY+4+i)==SOLID)fill.add(answered.get(i));
   var server=mc.getSingleplayerServer();
   if(!fill.isEmpty())server.execute(()->{
    var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
    for(var p:fill){
     if(cells.putIfAbsent(p.pos().asLong(),p.depth()|p.material()<<MATERIAL_SHIFT)!=null)continue;
     dirty=true;
     var state=level.getBlockState(p.pos());
     if(!state.isAir()&&!state.is(Blocks.BARRIER))continue;
     var block=p.depth()>=DEPTH_BEDROCK?Blocks.BEDROCK:p.depth()>=DEPTH_STONE?Blocks.STONE:subsoil(p.material());
     level.setBlock(p.pos(),block.defaultBlockState(),3);
    }
   });
  }
  if(queue.isEmpty())return;
  var batch=new ArrayList<Pending>();
  while(batch.size()<CLASSIFY_MAX){var p=queue.poll();if(p==null)break;if(!cells.containsKey(p.pos().asLong()))batch.add(p);}
  if(batch.isEmpty())return;
  int origin=(int)Passthrough.origin();
  shm.mem.putInt(CLASSIFY_REQUEST+4,epoch);shm.mem.putInt(CLASSIFY_REQUEST+8,batch.size());
  for(int i=0;i<batch.size();i++){
   var p=batch.get(i).pos();int o=CLASSIFY_REQUEST+12+i*12;
   shm.mem.putInt(o,p.getX()-origin);shm.mem.putInt(o+4,p.getY()-1024);shm.mem.putInt(o+8,p.getZ());
  }
  if(++serial==0)serial=1;
  asked=batch;askedTicks=0;
  shm.set(CLASSIFY_REQUEST,serial);
 }

 // --- server: block changes -------------------------------------------------------
 /** A generated block was removed by anything (mining, TNT, pistons): its cell is now dug out. */
 public static void changed(ServerLevel level,BlockPos pos,BlockState before,BlockState after){
  if(!loaded||!level.dimension().equals(NativeBlocks.DIMENSION)||!after.isAir()||before.isAir()||before.is(Blocks.BARRIER))return;
  Integer cell=cells.get(pos.asLong());
  if(cell==null||(cell&CARVED)!=0)return;
  carve(pos.immutable(),cell&DEPTH,(cell>>MATERIAL_SHIFT)&0xff);
 }
 /** Zelda surface material to the block it mines as; null where digging is not allowed. */
 private static Block surfaceBlock(int material){
  return switch(material){
   case 1->Blocks.SAND;
   case 2->Blocks.STONE;
   case 9,10,13->Blocks.OAK_PLANKS;
   case 12->Blocks.PACKED_ICE;
   case 3,4,5,7->null; // water, lava and living surfaces
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
  cells.clear();queue.clear();asked=List.of();file=path;loaded=true;mapDirty=true;dirty=false;
  if(!Files.isRegularFile(path))return;
  try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
   if(in.readInt()!=2)return; // an earlier layout: those digs keep their blocks but lose their openings
   for(int n=in.readInt();n>0;n--)cells.put(in.readLong(),in.readInt());
  }catch(IOException e){System.err.println("Could not read "+path+": "+e);}
 }
 private static void save(){
  dirty=false;
  try{
   var temp=file.resolveSibling(file.getFileName()+".tmp");
   try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
    out.writeInt(2);
    var entries=new ArrayList<>(cells.entrySet());out.writeInt(entries.size());
    for(var e:entries){out.writeLong(e.getKey());out.writeInt(e.getValue());}
   }
   Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  }catch(IOException e){System.err.println("Could not save "+file+": "+e);}
 }
}
