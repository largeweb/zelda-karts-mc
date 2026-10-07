package local.composite;
import java.lang.invoke.VarHandle;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Optional;
import java.util.OptionalDouble;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
/**
 * Link as a guest in another game's world. A second copy of the Zelda engine runs
 * beside the host game and draws only Link and what he uses; it is sent the same
 * position, camera and controls as the host, and its picture is laid over the host's
 * wherever Link is nearer than what the host shows.
 */
public final class Guest {
 /** Guest shared memory and frame sit beside the host's, with this suffix. */
 public static final String SUFFIX="-guest";
 public static final RenderPipeline PIPELINE=RenderPipeline.builder()
  .withLocation(Identifier.parse("hyrule:pipeline/guest_frame"))
  .withVertexShader(Identifier.parse("hyrule:core/zelda_frame")).withFragmentShader(Identifier.parse("hyrule:core/guest_frame"))
  .withBindGroupLayout(BindGroupLayout.builder().withUniform("GuestColor",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("GuestDepth",UniformType.COMBINED_IMAGE_SAMPLER).build())
  .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withColorTargetState(ColorTargetState.DEFAULT)
  // Reversed depth: nearer is greater. Link shows only in front of the host's picture.
  .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true)).build();
 // What is copied from the host's shared memory each tick: player, controls, requests, camera.
 private static final int[][] MIRRORED={{192,52},{768,60},{832,32},{1280,32}};
 private static final int HIDDEN=2;
 private static Shared shm;private static int retry;
 private static MappedByteBuffer map;private static TextureTarget color,depth;
 private static ByteBuffer pixels,staging;private static int sequence,width,height,uploaded=-1;private static long changed;
 /** True while a guest engine is drawing Link, so Minecraft does not draw its own player. */
 public static boolean active(){return shm!=null&&System.nanoTime()-changed<1_000_000_000L;}
 public static void reset(){shm=null;map=null;pixels=null;changed=0;}
 /** Pass the host's latest state on to the guest. Hidden while the player rides something in the host game. */
 public static void tick(Shared host,boolean hidden){
  var base=System.getProperty("composite.shm");
  if(shm==null){
   if(retry++%20!=0)return;
   try{var path=Path.of(base+SUFFIX);if(!Files.exists(path))return;shm=new Shared(path);}catch(Exception e){return;}
  }
  var port=shm.snapshot(64,56);if(port==null)return;
  int epoch=port.getInt(0);
  shm.f(60,WorldFrame.far);
  for(int[] section:MIRRORED){
   var data=host.snapshot(section[0],section[1]);if(data==null)continue;
   data.putInt(0,epoch); // each engine checks messages against its own session number
   if(section[0]==832&&hidden)data.putInt(20,data.getInt(20)|HIDDEN);
   shm.publish(section[0],data);
  }
  read(base);
 }
 /** Take the guest's newest frame: colour, then one float of depth per pixel. */
 private static void read(String base){
  try{
   if(map==null){var path=Path.of(base+SUFFIX+".rgba");if(!Files.exists(path))return;try(var file=FileChannel.open(path,StandardOpenOption.READ)){map=file.map(FileChannel.MapMode.READ_ONLY,0,file.size());}map.order(ByteOrder.LITTLE_ENDIAN);}
   int seq=(int)WorldFrame.INT.getAcquire(map,0),w=map.getInt(8),h=map.getInt(12);
   if(map.getInt(4)!=0x46524D32||w<1||h<1||w>2560||h>1440||seq==sequence||(seq&1)!=0)return;
   int size=w*h*8;
   if(staging==null||staging.capacity()!=size)staging=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
   staging.clear();staging.put(map.slice(64,size));staging.flip();VarHandle.acquireFence();
   if(seq!=(int)WorldFrame.INT.getAcquire(map,0))return;
   var old=pixels;pixels=staging;staging=old;sequence=seq;width=w;height=h;changed=System.nanoTime();
  }catch(Exception e){map=null;}
 }
 /** Lay Link over the host's picture. Called right after the host frame is drawn. */
 public static void draw(){
  if(!active()||pixels==null)return;
  var pipeline=RenderSystem.getCompiledPipelineNullable(PIPELINE);if(pipeline==null)return;
  var encoder=RenderSystem.getDevice().createCommandEncoder();
  if(uploaded!=sequence){
   if(color==null||color.width!=width||color.height!=height){
    if(color!=null){color.destroyBuffers();depth.destroyBuffers();}
    color=new TextureTarget("Guest frame colour",width,height,GpuFormat.RGBA8_UNORM,null);
    depth=new TextureTarget("Guest frame depth",width,height,GpuFormat.R32_FLOAT,null);
   }
   int bytes=width*height*4;
   encoder.writeToTexture(color.getColorTexture(),pixels.slice(0,bytes),0,0,0,0,width,height);
   encoder.writeToTexture(depth.getColorTexture(),pixels.slice(bytes,bytes),0,0,0,0,width,height);
   uploaded=sequence;
  }
  var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
  try(var pass=encoder.createRenderPass(()->"Guest frame over host",target.getColorTextureView(),Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
   pass.setPipeline(pipeline);var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
   pass.setUniform("GuestColor",color.getColorTextureView(),sampler);pass.setUniform("GuestDepth",depth.getColorTextureView(),sampler);
   pass.draw(3,1,0,0);
  }
  encoder.submit();
 }
}
