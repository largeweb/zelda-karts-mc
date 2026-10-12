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
 * What lies behind the surface is worked out lazily. When a cell is carved, Zelda is
 * asked about each neighbour. One that is solid (no scenery through it, no floor under
 * it) is filled with soil, then stone further in. One that has scenery running through
 * it stays as Zelda draws it, but counts as a solid block from inside the dig until it
 * is mined too: Zelda's scenery is a hollow shell, and the space behind it is the void.
 * One that is open air is left alone. So dug space is always closed in, and every way
 * out of it leads either to open ground or to something that can be mined.
 */
public final class Digging {
 // Per cell: how many cells in from the original surface, the surface material, what
 // the cell is, and for a cell with a floor through it how high that floor sits.
 private static final int DEPTH=0xff,MATERIAL_SHIFT=8,KIND_SHIFT=16,KIND=3<<KIND_SHIFT,HEIGHT_SHIFT=18;
 private static final int FILLED=0,CARVED=1<<KIND_SHIFT,OPEN=2<<KIND_SHIFT,SURFACE=3<<KIND_SHIFT;
 // Set on a dug cell that held generated soil or stone rather than Zelda scenery.
 private static final int WAS_FILLED=1<<26;
 private static final Map<Long,Integer> cells=new ConcurrentHashMap<>();
 private static final int MAP=64,LAYERS=24,DEPTH_STONE=4,DEPTH_BEDROCK=24;
 /**
  * For each cell with scenery through it, that surface as a plane in the cell's own 0..1
  * coordinates: normal towards the open side, then its offset. Behind the plane is the
  * solid part of the cell: drawn as soil where a dig exposes it, and solid to the player.
  */
 private static final Map<Long,float[]> planes=new ConcurrentHashMap<>();
 private static final Map<Long,net.minecraft.world.phys.shapes.VoxelShape> shapes=new ConcurrentHashMap<>();
 private static final int CLASSIFY_PLANES=5700;private static boolean refreshDisplays;
 private static final int HIT=4608,CLASSIFY_REQUEST=4672,CLASSIFY_REPLY=4928,CLASSIFY_MAX=16;
 private static final int CARVED_GRID=5120,GRID=16;
 private static Path file;private static boolean loaded,dirty;private static int ticks;
 private static int mapX=Integer.MIN_VALUE,mapY,mapZ;private static volatile boolean mapDirty=true;
 // The Zelda surface cell being mined right now, for the crack overlay.
 private static BlockPos mining;private static float progress;private static int stage,swingTicks;
 // Neighbours waiting for Zelda to say whether they are solid.
 private record Pending(BlockPos pos,int depth,int material){}
 private static final ConcurrentLinkedQueue<Pending> queue=new ConcurrentLinkedQueue<>();
 private static List<Pending> asked=List.of();private static int serial,askedTicks;

 private static int kind(Integer cell){return cell==null?-1:cell&KIND;}
 private static boolean carved(int x,int y,int z){return kind(cells.get(BlockPos.asLong(x,y,z)))==CARVED;}
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
   if(kind(e.getValue())!=CARVED)continue;
   long key=e.getKey();int x=BlockPos.getX(key)-mapX,y=BlockPos.getY(key)-mapY,z=BlockPos.getZ(key)-mapZ;
   if(x>=0&&x<MAP&&z>=0&&z<MAP&&y>=0&&y<LAYERS)bits[z*MAP+x]|=1<<y;
  }
  var bytes=ByteBuffer.allocateDirect(MAP*MAP*4).order(ByteOrder.nativeOrder());
  for(int b:bits)bytes.putFloat(b);
  return bytes.flip();
 }

 /**
  * Show the solid part of a scenery cell beside a dig: block displays, squashed to fit
  * behind the cell's surface. They are ordinary Minecraft rendering, so they look like
  * the blocks lining the dig at any distance, and the game's surface still covers them
  * from outside. They have no hitbox; solidPart() is what the player collides with.
  */
 private static void showSolidPart(ServerLevel level,BlockPos pos,float[] plane,Block block){
  var name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
  var source=level.getServer().createCommandSourceStack().withSuppressedOutput();
  // Whole-number coordinates in a command mean the middle of the block; these are its corner.
  // One surface needs few pieces; where surfaces meet, more are needed to follow the corner.
  for(double[] box:pieces(plane,plane.length>4?4:2,true)){
   var command=String.format(java.util.Locale.ROOT,
    "execute in composite:zelda run summon minecraft:block_display %d.0 %d.0 %d.0 {block_state:\"%s\",brightness:{sky:15,block:0},Tags:[\"hyrule_partial\",\"%s\"],"
    +"transformation:{translation:[%.4ff,%.4ff,%.4ff],scale:[%.4ff,%.4ff,%.4ff],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}",
    pos.getX(),pos.getY(),pos.getZ(),name,tag(pos),box[0],box[1],box[2],box[3]-box[0],box[4]-box[1],box[5]-box[2]);
   level.getServer().getCommands().performPrefixedCommand(source,command);
  }
 }
 private static void hideSolidPart(ServerLevel level,BlockPos pos){
  level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
   "execute in composite:zelda run kill @e[type=minecraft:block_display,tag="+tag(pos)+"]");
 }
 private static String tag(BlockPos pos){return "hp_"+pos.getX()+"_"+pos.getY()+"_"+pos.getZ();}
 /**
  * The solid part of a scenery cell as boxes {x0,y0,z0,x1,y1,z1} in the cell's 0..1
  * coordinates. The cell holds one to three surfaces (four floats each); solid is what
  * lies behind all of them. It is built as an n by n bundle of columns along the axis
  * the first surface faces most, each filled as far as the surfaces allow. Cautious
  * columns stop at the lowest point across the column, so nothing shows through a
  * surface; otherwise at its middle.
  */
 private static List<double[]> pieces(float[] surfaces,int n,boolean cautious){
  var boxes=new ArrayList<double[]>();
  int axis=Math.abs(surfaces[1])>=Math.abs(surfaces[0])&&Math.abs(surfaces[1])>=Math.abs(surfaces[2])?1:Math.abs(surfaces[0])>=Math.abs(surfaces[2])?0:2;
  int a=axis==0?1:0,b=axis==2?1:2; // the two axes across the columns
  for(int i=0;i<n;i++)columns:for(int j=0;j<n;j++){
   double u0=(double)i/n,u1=(i+1.0)/n,v0=(double)j/n,v1=(j+1.0)/n,from=0,to=1;
   for(int k=0;k<surfaces.length;k+=4){
    double along=surfaces[k+axis],d=surfaces[k+3];
    // How far the rest of the plane's equation swings across this column's cross-section.
    double low=Double.MAX_VALUE,high=-Double.MAX_VALUE;
    for(double u:cautious?new double[]{u0,u1}:new double[]{(u0+u1)/2})for(double v:cautious?new double[]{v0,v1}:new double[]{(v0+v1)/2}){
     double rest=surfaces[k+a]*u+surfaces[k+b]*v;low=Math.min(low,rest);high=Math.max(high,rest);
    }
    if(Math.abs(along)<.05){
     // The surface runs along the column: the whole column is either behind it or not.
     if(high>d+1e-3)continue columns;
    }else if(along>0)to=Math.min(to,(d-high)/along);   // solid lies below the cut
    else from=Math.max(from,(d-high)/along);           // solid lies above it (dividing by a negative turns the bound round)
   }
   from=Math.max(from,0);to=Math.min(to,1);
   if(to-from<.02)continue;
   double[] lo=new double[3],hi=new double[3];
   lo[axis]=from;hi[axis]=to;lo[a]=u0;hi[a]=u1;lo[b]=v0;hi[b]=v1;
   boxes.add(new double[]{lo[0],lo[1],lo[2],hi[0],hi[1],hi[2]});
  }
  return boxes;
 }

 // --- client: per tick ------------------------------------------------------------
 public static void tick(Minecraft mc,Shared shm,int epoch){
  var server=mc.getSingleplayerServer();
  if(server==null&&mc.player!=null&&Remote.inHyrule()){
   if(!remote){flush();remote=true;forget();}
   if(++ticks%5==0)survey(mc);
   publishCarved(mc,shm,epoch);
   mine(mc,shm,epoch);
   classify(mc,shm,epoch);
   return;
  }
  if(remote&&server!=null){remote=false;forget();loaded=false;}
  if(server==null||mc.player==null)return;
  load(server.getWorldPath(LevelResource.ROOT));
  if(refreshDisplays){
   // Once per world load: make sure every scenery cell beside a dig has its displays
   // (worlds dug before displays existed have none).
   refreshDisplays=false;
   var all=new ArrayList<>(planes.entrySet());
   server.execute(()->{
    var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
    for(var e:all){
     Integer cell=cells.get(e.getKey());if(kind(cell)!=SURFACE)continue;
     var pos=BlockPos.of(e.getKey());
     hideSolidPart(level,pos);
     showSolidPart(level,pos,e.getValue(),(cell&DEPTH)>=DEPTH_STONE?Blocks.STONE:subsoil((cell>>MATERIAL_SHIFT)&0xff));
    }
   });
  }
  publishCarved(mc,shm,epoch);
  mine(mc,shm,epoch);
  classify(mc,shm,epoch);
  if(dirty&&++ticks%100==0)save();
 }

 /** Zelda ignores its own scenery inside dug cells; it is told which those are around the player. */
 private static int gridX=Integer.MIN_VALUE,gridY,gridZ;private static boolean gridDirty=true;private static int gridEpoch;
 private static void publishCarved(Minecraft mc,Shared shm,int epoch){
  var at=mc.player.blockPosition();
  if(gridX==Integer.MIN_VALUE||Math.abs(at.getX()-gridX-GRID/2)>3||Math.abs(at.getY()-gridY-GRID/2)>3||Math.abs(at.getZ()-gridZ-GRID/2)>3){
   gridX=at.getX()-GRID/2;gridY=at.getY()-GRID/2;gridZ=at.getZ()-GRID/2;gridDirty=true;
  }
  if(!gridDirty&&gridEpoch==epoch)return;
  gridDirty=false;gridEpoch=epoch;
  byte[] bits=new byte[GRID*GRID*GRID/8];int count=0;
  for(int y=0;y<GRID;y++)for(int z=0;z<GRID;z++)for(int x=0;x<GRID;x++)if(carved(gridX+x,gridY+y,gridZ+z)){
   int index=(y*GRID+z)*GRID+x;bits[index>>3]|=1<<(index&7);count++;
  }
  var payload=Passthrough.buffer(20+bits.length);
  payload.putInt(epoch).putInt(count).putInt(gridX-(int)Passthrough.origin()).putInt(gridY-1024).putInt(gridZ).put(bits);
  shm.publish(CARVED_GRID,payload);
 }

 /**
  * Extra solids for the player while inside a dig. The cells around dug space that are
  * neither dug, nor open air, nor real blocks are Zelda scenery seen from its hollow
  * side, or not yet identified; from in here they are solid, so nothing leads to the void.
  * A cell with a floor through it is solid up to that floor, so it can be stepped onto.
  */
 public static List<net.minecraft.world.phys.shapes.VoxelShape> solids(net.minecraft.world.entity.player.Player player){
  if(cells.isEmpty())return List.of();
  var box=player.getBoundingBox();
  int x=(int)Math.floor(player.getX()),z=(int)Math.floor(player.getZ());
  if(!carved(x,(int)Math.floor(box.minY+.05),z)&&!carved(x,(int)Math.floor(box.getCenter().y),z))return List.of();
  var shapes=new ArrayList<net.minecraft.world.phys.shapes.VoxelShape>();
  for(int cx=x-1;cx<=x+1;cx++)for(int cz=z-1;cz<=z+1;cz++)for(int cy=(int)Math.floor(box.minY)-1;cy<=(int)Math.floor(box.maxY)+1;cy++){
   Integer cell=cells.get(BlockPos.asLong(cx,cy,cz));
   int kind=kind(cell);
   if(kind==CARVED||kind==OPEN||kind==FILLED)continue; // real blocks collide by themselves
   if(kind==-1&&!besideCarved(cx,cy,cz))continue;
   long key=BlockPos.asLong(cx,cy,cz);
   var shape=kind==SURFACE?solidPart(key):null;
   shapes.add((shape==null?net.minecraft.world.phys.shapes.Shapes.block():shape).move(cx,cy,cz));
  }
  return shapes;
 }
 /** The part of a scenery cell behind its surface, as a collision shape of quarter-block columns. */
 private static net.minecraft.world.phys.shapes.VoxelShape solidPart(long key){
  var plane=planes.get(key);
  if(plane==null)return null;
  return shapes.computeIfAbsent(key,k->{
   var shape=net.minecraft.world.phys.shapes.Shapes.empty();
   for(double[] box:pieces(plane,4,false))
    shape=net.minecraft.world.phys.shapes.Shapes.or(shape,net.minecraft.world.phys.shapes.Shapes.create(new net.minecraft.world.phys.AABB(box[0],box[1],box[2],box[3],box[4],box[5])));
   return shape;
  });
 }
 private static boolean besideCarved(int x,int y,int z){
  return carved(x+1,y,z)||carved(x-1,y,z)||carved(x,y+1,z)||carved(x,y-1,z)||carved(x,y,z+1)||carved(x,y,z-1);
 }
 /** From inside a dig: the first solid-but-unseen cell along the line of sight, to mine it like a block. */
 private static BlockPos behindScenery(Minecraft mc){
  var eye=mc.player.getEyePosition();var look=mc.player.getLookAngle();
  if(!carved(BlockPos.containing(eye))&&!carved(mc.player.blockPosition()))return null;
  BlockPos last=null;
  for(double t=0;t<=4.5;t+=.1){
   var pos=BlockPos.containing(eye.add(look.scale(t)));
   if(pos.equals(last))continue;
   last=pos;
   int kind=kind(cells.get(pos.asLong()));
   if(kind==CARVED)continue;
   return kind==SURFACE||(kind==-1&&besideCarved(pos.getX(),pos.getY(),pos.getZ()))?pos:null;
  }
  return null;
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
  // Nothing in view to mine from this side: scenery seen from behind, inside a dig.
  if(target==null&&Passthrough.interactive()&&NativeBlocks.inDimension()&&mc.gui.screen()==null&&mc.options.keyAttack.isDown()
     &&!NativeButtons.empty(mc)&&!ZeldaItems.holding(mc)&&(mc.hitResult==null||mc.hitResult.getType()==HitResult.Type.MISS)){
   target=behindScenery(mc);
   if(target!=null){Integer cell=cells.get(target.asLong());material=cell==null?0:(cell>>MATERIAL_SHIFT)&0xff;surface=Blocks.DIRT;}
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
  if(server==null){
   // On a server: ask it. If it agrees the cell becomes a dug cell for everyone, and comes back here as one.
   materials.put(pos.asLong(),m);
   // Asked once; the answer takes a moment to come back as a dug cell.
   long now=mc.level.getGameTime();
   if(now-lined.getOrDefault(pos.asLong(),-1000L)<20)return;
   lined.put(pos.asLong(),now);
   Remote.command("hyrule dig "+pos.getX()+" "+pos.getY()+" "+pos.getZ()+" "+m);
   return;
  }
  server.execute(()->{
   var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
   var existing=level.getBlockState(pos);
   if(planes.remove(pos.asLong())!=null){shapes.remove(pos.asLong());hideSolidPart(level,pos);}
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
  if(old!=null&&(old&DEPTH)!=0){depth=old&DEPTH;material=(old>>MATERIAL_SHIFT)&0xff;}
  cells.put(pos.asLong(),depth|material<<MATERIAL_SHIFT|CARVED|(kind(old)==FILLED?WAS_FILLED:0));
  mapDirty=true;gridDirty=true;dirty=true;
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
   var fill=new ArrayList<Pending>();var partial=new ArrayList<Pending>();
   for(int i=0;i<answered.size();i++){
    var p=answered.get(i);int answer=Byte.toUnsignedInt(shm.mem.get(CLASSIFY_REPLY+4+i)),base=p.depth()|p.material()<<MATERIAL_SHIFT;
    if(remote){
     // On a server the answers are kept for the survey, which works out the cells afresh.
     if(answer>1){
      int o=CLASSIFY_PLANES+i*48,count=0;float[] found=new float[12];
      for(int k=0;k<3;k++){
       float nx=shm.mem.getFloat(o+k*16),ny=shm.mem.getFloat(o+k*16+4),nz=shm.mem.getFloat(o+k*16+8);
       if(nx==0&&ny==0&&nz==0)continue;
       float d=shm.mem.getFloat(o+k*16+12);
       if(ny>.99f&&answer>=3)d=Math.min(d,(answer-3)/252f);
       found[count*4]=nx;found[count*4+1]=ny;found[count*4+2]=nz;found[count*4+3]=d;count++;
      }
      // Scenery that crosses a cell without a usable surface is treated as open.
      if(count>0)knownPlanes.put(p.pos().asLong(),java.util.Arrays.copyOf(found,count*4));else answer=0;
     }
     known.put(p.pos().asLong(),answer);
     continue;
    }
    if(answer==1)fill.add(p); // solid: becomes a real block below
    else if(answer==0)cells.putIfAbsent(p.pos().asLong(),base|OPEN);
    // Scenery runs through it; its plane says which part of the cell is solid.
    else if(cells.putIfAbsent(p.pos().asLong(),base|SURFACE|(answer<3?0:Math.max(1,answer-3))<<HEIGHT_SHIFT)==null){
     // Up to three surfaces through the cell, four floats each; unused ones are zero.
     int o=CLASSIFY_PLANES+i*48,count=0;
     float[] found=new float[12];
     for(int k=0;k<3;k++){
      float nx=shm.mem.getFloat(o+k*16),ny=shm.mem.getFloat(o+k*16+4),nz=shm.mem.getFloat(o+k*16+8);
      if(nx==0&&ny==0&&nz==0)continue;
      float d=shm.mem.getFloat(o+k*16+12);
      // Collision is sometimes a ledge higher than what is drawn (simplified steps). A flat
      // floor is never taken higher than the floor measured at the cell's middle.
      if(ny>.99f&&answer>=3)d=Math.min(d,(answer-3)/252f);
      found[count*4]=nx;found[count*4+1]=ny;found[count*4+2]=nz;found[count*4+3]=d;count++;
     }
     if(count>0){planes.put(p.pos().asLong(),java.util.Arrays.copyOf(found,count*4));partial.add(p);}
    }
    dirty=true;
   }
   var server=mc.getSingleplayerServer();
   if(server!=null&&(!fill.isEmpty()||!partial.isEmpty()))server.execute(()->{
    var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
    for(var p:partial)showSolidPart(level,p.pos(),planes.get(p.pos().asLong()),p.depth()>=DEPTH_STONE?Blocks.STONE:subsoil(p.material()));
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
  while(batch.size()<CLASSIFY_MAX){var p=queue.poll();if(p==null)break;if(remote?!known.containsKey(p.pos().asLong()):!cells.containsKey(p.pos().asLong()))batch.add(p);}
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

 /**
  * Using a hoe on a block placed in a dug cell puts back what was dug there: Zelda's
  * scenery where the cell held scenery, or the soil or stone that was generated for it.
  * The placed block comes back as an item. Returns true if the click was a restore.
  */
 public static boolean restore(Minecraft mc){
  if(!Passthrough.interactive()||!NativeBlocks.inDimension()||mc.player==null||!mc.player.getMainHandItem().is(net.minecraft.tags.ItemTags.HOES))return false;
  if(!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return false;
  var pos=hit.getBlockPos().immutable();Integer cell=cells.get(pos.asLong());
  if(remote){
   // On a server a block put into a dug cell has already closed it; the hoe takes the block
   // back out and leaves the game's own surface.
   if(!dug.contains(pos.asLong()))return false;
   mc.player.swing(InteractionHand.MAIN_HAND,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);
   Remote.command("hyrule restore "+pos.getX()+" "+pos.getY()+" "+pos.getZ());
   return true;
  }
  if(kind(cell)!=CARVED)return false;
  var server=mc.getSingleplayerServer();boolean drops=!mc.player.getAbilities().instabuild;
  int depth=cell&DEPTH,material=(cell>>MATERIAL_SHIFT)&0xff;boolean soil=(cell&WAS_FILLED)!=0;
  // The cell stops being dug first, so removing the placed block is not taken for digging.
  if(soil)cells.put(pos.asLong(),depth|material<<MATERIAL_SHIFT);else cells.remove(pos.asLong());
  mapDirty=true;gridDirty=true;dirty=true;
  mc.player.swing(InteractionHand.MAIN_HAND,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);
  server.execute(()->{
   var level=server.getLevel(NativeBlocks.DIMENSION);if(level==null)return;
   var placed=level.getBlockState(pos);
   if(drops)Block.dropResources(placed,level,pos);
   var original=depth>=DEPTH_BEDROCK?Blocks.BEDROCK:depth>=DEPTH_STONE?Blocks.STONE:subsoil(material);
   level.setBlock(pos,(soil?original:Blocks.AIR).defaultBlockState(),3);
   level.levelEvent(2001,pos,Block.getId(soil?original.defaultBlockState():placed));
  });
  return true;
 }

 // --- server: block changes -------------------------------------------------------
 /** A generated block was removed by anything (mining, TNT, pistons): its cell is now dug out. */
 public static void changed(ServerLevel level,BlockPos pos,BlockState before,BlockState after){
  if(!loaded||!level.dimension().equals(NativeBlocks.DIMENSION)||!after.isAir()||before.isAir()||before.is(Blocks.BARRIER))return;
  Integer cell=cells.get(pos.asLong());
  if(kind(cell)!=FILLED)return;
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

 /** Write out digs not yet saved; called when the world is being left. */
 public static void flush(){if(dirty&&loaded&&!remote)save();}

 // --- on a server -----------------------------------------------------------------
 // The server marks each dug cell with an invisible block (a light block of level 0), so
 // every player's game sees the same holes just by seeing the world. Each client works
 // out the rest for itself from its own Zelda engine: which neighbouring cells are
 // open, which hold part of the scenery (drawn here as block displays only this
 // client has), and which are solid and need a real block, which it asks the server for.
 private static boolean remote;
 /** What Zelda said about a cell (0 open, 1 solid, more: scenery through it); never changes for a place. */
 private static final Map<Long,Integer> known=new ConcurrentHashMap<>();
 private static final Map<Long,float[]> knownPlanes=new ConcurrentHashMap<>();
 /** Surface material of cells this player dug, so what lines them matches. */
 private static final Map<Long,Integer> materials=new ConcurrentHashMap<>();
 /** Cells seen dug at some time: a block standing in one can be taken out with a hoe. */
 private static final java.util.Set<Long> dug=ConcurrentHashMap.newKeySet();
 private static final Map<Long,Long> lined=new HashMap<>();
 private static final Map<Long,List<net.minecraft.world.entity.Entity>> displays=new HashMap<>();
 private static int displayId=-1000;
 private static void forget(){
  cells.clear();planes.clear();shapes.clear();queue.clear();asked=List.of();known.clear();knownPlanes.clear();materials.clear();dug.clear();lined.clear();
  displays.clear();mapDirty=gridDirty=true;mining=null;
 }
 private static boolean marker(BlockState state){return state.is(Blocks.LIGHT)&&state.getValue(net.minecraft.world.level.block.LightBlock.LEVEL)==0;}
 /** Read the dug cells around the player from the world and rebuild everything that follows from them. */
 private static void survey(Minecraft mc){
  var level=mc.level;var at=mc.player.blockPosition();var cursor=new BlockPos.MutableBlockPos();
  var found=new HashMap<Long,Integer>();var surfaces=new HashMap<Long,float[]>();
  var markers=new ArrayList<BlockPos>();
  for(int x=-20;x<=20;x++)for(int z=-20;z<=20;z++)for(int y=-10;y<=10;y++){
   cursor.set(at.getX()+x,at.getY()+y,at.getZ()+z);
   if(marker(level.getBlockState(cursor))){markers.add(cursor.immutable());found.put(cursor.asLong(),CARVED);}
  }
  long now=level.getGameTime();
  for(var pos:markers){
   dug.add(pos.asLong());
   int material=materials.getOrDefault(pos.asLong(),-1);
   for(var direction:Direction.values()){
    var next=pos.relative(direction);long key=next.asLong();
    if(found.containsKey(key))continue;
    if(material>=0)materials.putIfAbsent(key,material);
    Integer answer=known.get(key);
    if(answer==null){queue.add(new Pending(next.immutable(),1,0));continue;} // not asked yet: solid to the player meanwhile
    if(answer==0)found.put(key,OPEN);
    else if(answer==1){
     var state=level.getBlockState(next);
     if(!state.isAir())found.put(key,FILLED);
     // Solid ground with nothing there: the server puts a block. Asked for again now and then until it is.
     else if(next.distSqr(at)<100&&now-lined.getOrDefault(key,-1000L)>60){
      lined.put(key,now);
      Remote.command("hyrule line "+next.getX()+" "+next.getY()+" "+next.getZ()+" "+materials.getOrDefault(key,-1));
     }
    }else{
     var surface=knownPlanes.get(key);
     if(surface==null)continue;
     found.put(key,SURFACE|Math.max(1,answer-3)<<HEIGHT_SHIFT);surfaces.put(key,surface);
    }
   }
  }
  if(found.equals(cells))return;
  cells.clear();cells.putAll(found);planes.clear();planes.putAll(surfaces);shapes.clear();mapDirty=gridDirty=true;
  // Block displays for the solid part of scenery cells, on this client only.
  for(var it=displays.entrySet().iterator();it.hasNext();){
   var e=it.next();
   if(surfaces.containsKey(e.getKey()))continue;
   for(var entity:e.getValue())level.removeEntity(entity.getId(),net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
   it.remove();
  }
  for(var e:surfaces.entrySet()){
   if(displays.containsKey(e.getKey()))continue;
   var pos=BlockPos.of(e.getKey());var made=new ArrayList<net.minecraft.world.entity.Entity>();
   var block=subsoil(materials.getOrDefault(e.getKey(),0)).defaultBlockState();
   for(double[] box:pieces(e.getValue(),e.getValue().length>4?4:2,true)){
    var display=localDisplay(level,pos,block,box);
    if(display!=null){level.addEntity(display);made.add(display);}
   }
   displays.put(e.getKey(),made);
  }
 }
 private static java.lang.reflect.Method setBlock,setTransformation,setBrightness;
 /** A block display that exists only in this client's world. Its setters are private to the game. */
 private static net.minecraft.world.entity.Entity localDisplay(net.minecraft.client.multiplayer.ClientLevel level,BlockPos pos,BlockState block,double[] box){
  try{
   if(setBlock==null){
    setBlock=net.minecraft.world.entity.Display.BlockDisplay.class.getDeclaredMethod("setBlockState",BlockState.class);
    setTransformation=net.minecraft.world.entity.Display.class.getDeclaredMethod("setTransformation",com.mojang.math.Transformation.class);
    setBrightness=net.minecraft.world.entity.Display.class.getDeclaredMethod("setBrightnessOverride",net.minecraft.util.Brightness.class);
    for(var method:new java.lang.reflect.Method[]{setBlock,setTransformation,setBrightness})method.setAccessible(true);
   }
   var display=new net.minecraft.world.entity.Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY,level);
   display.setId(displayId--);
   display.setPos(pos.getX(),pos.getY(),pos.getZ());
   setBlock.invoke(display,block);
   setTransformation.invoke(display,new com.mojang.math.Transformation(new org.joml.Vector3f((float)box[0],(float)box[1],(float)box[2]),new org.joml.Quaternionf(),
    new org.joml.Vector3f((float)(box[3]-box[0]),(float)(box[4]-box[1]),(float)(box[5]-box[2])),new org.joml.Quaternionf()));
   setBrightness.invoke(display,new net.minecraft.util.Brightness(0,15));
   return display;
  }catch(ReflectiveOperationException e){System.err.println("Could not make a block display: "+e);return null;}
 }

 // --- persistence: one small file beside the world --------------------------------
 private static void load(Path world){
  var path=world.resolve("hyrule-digging.dat");
  if(loaded&&path.equals(file))return;
  cells.clear();planes.clear();shapes.clear();queue.clear();asked=List.of();file=path;loaded=true;mapDirty=true;dirty=false;refreshDisplays=true;
  if(!Files.isRegularFile(path))return;
  try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
   int version=in.readInt();
   if(version!=4&&version!=5)return; // an earlier layout: those digs keep their blocks but lose their openings
   for(int n=in.readInt();n>0;n--)cells.put(in.readLong(),in.readInt());
   for(int n=in.readInt();n>0;n--){
    long key=in.readLong();float[] surfaces=new float[version==4?4:in.readInt()]; // version 4 kept one surface per cell
    for(int i=0;i<surfaces.length;i++)surfaces[i]=in.readFloat();
    planes.put(key,surfaces);
   }
  }catch(IOException e){System.err.println("Could not read "+path+": "+e);}
 }
 private static void save(){
  dirty=false;
  try{
   var temp=file.resolveSibling(file.getFileName()+".tmp");
   try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
    out.writeInt(5);
    var entries=new ArrayList<>(cells.entrySet());out.writeInt(entries.size());
    for(var e:entries){out.writeLong(e.getKey());out.writeInt(e.getValue());}
    var surfaces=new ArrayList<>(planes.entrySet());out.writeInt(surfaces.size());
    for(var e:surfaces){out.writeLong(e.getKey());out.writeInt(e.getValue().length);for(float f:e.getValue())out.writeFloat(f);}
   }
   Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  }catch(IOException e){System.err.println("Could not save "+file+": "+e);}
 }
}
