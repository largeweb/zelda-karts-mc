package local.composite;
import java.lang.invoke.*;
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
 * Zelda's latest frame: colour, depth and the camera it was rendered with, taken as one
 * snapshot before Minecraft culls. Minecraft renders its blocks with that same camera and
 * over that depth, so blocks stay registered to the picture and hide behind Zelda's walls.
 */
public final class WorldFrame {
 /** Near plane Minecraft renders with while composited; Zelda converts its depth for this value. */
 public static final float NEAR=.05f;
 private static final int MAGIC=0x46524D32,HEADER=64;
 public static final RenderPipeline PIPELINE=RenderPipeline.builder()
  .withLocation(Identifier.parse("hyrule:pipeline/zelda_frame"))
  .withVertexShader(Identifier.parse("hyrule:core/zelda_frame")).withFragmentShader(Identifier.parse("hyrule:core/zelda_frame"))
  .withBindGroupLayout(BindGroupLayout.builder().withUniform("ZeldaColor",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("ZeldaDepth",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Carve",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Params",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Cracks",UniformType.COMBINED_IMAGE_SAMPLER).build())
  .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).withColorTargetState(ColorTargetState.DEFAULT)
  .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,true)).build();
 public static final VarHandle INT=MethodHandles.byteBufferViewVarHandle(int[].class,ByteOrder.LITTLE_ENDIAN);
 static MappedByteBuffer map;static FileChannel file;static TextureTarget color,depth,carve,params;
 /** Far plane Minecraft renders with while composited. */
 public static float far=1024;
 private static final int PARAMS=24;
 private static final ByteBuffer paramBytes=ByteBuffer.allocateDirect(PARAMS*4).order(ByteOrder.nativeOrder());
 static ByteBuffer pixels,staging;static int sequence,producer,width,height,uploaded=-1;static long changed;
 static FrameCamera camera;static float light=1;
 public record FrameCamera(int epoch,float x,float y,float z,float yaw,float pitch,float fov,int valid,int request,int scene){}
 public static float light(){return light;}
 public static int sequence(){return sequence;}
 public static FrameCamera prepare(){
  if(!Passthrough.active())return null;
  try{
   if(map==null){var path=Path.of(System.getProperty("composite.shm")+".rgba");if(!Files.exists(path))return null;file=FileChannel.open(path,StandardOpenOption.READ);map=file.map(FileChannel.MapMode.READ_ONLY,0,file.size());map.order(ByteOrder.LITTLE_ENDIAN);}
   int seq=(int)INT.getAcquire(map,0),w=map.getInt(8),h=map.getInt(12),pid=map.getInt(16);
   if(map.getInt(4)!=MAGIC||w<1||h<1||w>2560||h>1440)return null;
   if((seq!=sequence||pid!=producer)&&(seq&1)==0){
    int size=w*h*8; // RGBA8 colour followed by one float of depth per pixel
    if(staging==null||staging.capacity()!=size)staging=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    var pose=new FrameCamera(map.getInt(20),map.getFloat(24),map.getFloat(28),map.getFloat(32),map.getFloat(36),map.getFloat(40),map.getFloat(44),map.getInt(48),map.getInt(52),map.getInt(56));
    float brightness=map.getFloat(60);
    staging.clear();staging.put(map.slice(HEADER,size));staging.flip();VarHandle.acquireFence();
    if(seq==(int)INT.getAcquire(map,0)){
     var old=pixels;pixels=staging;staging=old;camera=pose;
     if(Float.isFinite(brightness)&&brightness>=.1f&&brightness<=1)light=brightness;
     if(pid!=producer)uploaded=-1;
     sequence=seq;producer=pid;width=w;height=h;changed=System.nanoTime();
    }
   }
   return camera!=null&&System.nanoTime()-changed<250_000_000L?camera:null;
  }catch(Exception e){throw new IllegalStateException("Zelda framebuffer snapshot failed",e);}
 }
 /**
  * What the shader needs to find the world position of each Zelda pixel and look it up
  * in the map of dug floor columns: the frame's camera as position, basis and lens,
  * as sixteen floats in a one-row texture (no uniform buffer needed).
  */
 private static void carveInputs(com.mojang.renderpearl.api.commands.CommandEncoder encoder){
  if(carve==null){carve=new TextureTarget("Dug cells",64,64,GpuFormat.R32_FLOAT,null);params=new TextureTarget("Zelda frame camera",PARAMS,1,GpuFormat.R32_FLOAT,null);}
  var mc=Minecraft.getInstance();
  var grid=mc.player==null?null:Digging.mapIfChanged(mc.player.blockPosition());
  if(grid!=null)encoder.writeToTexture(carve.getColorTexture(),grid,0,0,0,0,64,64);
  var f=paramBytes.asFloatBuffer();
  boolean enabled=camera!=null&&Digging.any()&&Passthrough.interactive();
  if(enabled){
   double yaw=Math.toRadians(camera.yaw()),pitch=Math.toRadians(camera.pitch()),tan=Math.tan(Math.toRadians(camera.fov())/2);
   float fx=(float)(-Math.sin(yaw)*Math.cos(pitch)),fy=(float)-Math.sin(pitch),fz=(float)(Math.cos(yaw)*Math.cos(pitch));
   float rx=(float)-Math.cos(yaw),rz=(float)-Math.sin(yaw);
   // up = right x forward
   float ux=-rz*fy,uy=rz*fx-rx*fz,uz=rx*fy;
   f.put(new float[]{(float)(camera.x()/40.0+Passthrough.origin()-Digging.mapX()),1024+camera.y()/40f,camera.z()/40f-Digging.mapZ(),(float)(tan*width/height),
    rx,0,rz,(float)tan, ux,uy,uz,NEAR, fx,fy,fz,far});
   // The Zelda surface being mined, for the crack overlay: column, floor height, on/off.
   var mining=Digging.mining();
   f.put(new float[]{mining==null?0:mining.getX()-Digging.mapX(),mining==null?0:mining.getY()-Digging.mapY(),mining==null?0:mining.getZ()-Digging.mapZ(),mining==null?0:1,Digging.mapY(),0,0,0});
  }else for(int i=0;i<PARAMS;i++)f.put(0);
  encoder.writeToTexture(params.getColorTexture(),paramBytes,0,0,0,0,PARAMS,1);
 }
 public static boolean draw(){
  if(!Passthrough.active()||pixels==null||System.nanoTime()-changed>2_000_000_000L)return false;
  var encoder=RenderSystem.getDevice().createCommandEncoder();
  if(uploaded!=sequence){
   if(color==null||color.width!=width||color.height!=height){
    if(color!=null){color.destroyBuffers();depth.destroyBuffers();}
    color=new TextureTarget("Zelda frame colour",width,height,GpuFormat.RGBA8_UNORM,null);
    depth=new TextureTarget("Zelda frame depth",width,height,GpuFormat.R32_FLOAT,null);
   }
   int bytes=width*height*4;
   encoder.writeToTexture(color.getColorTexture(),pixels.slice(0,bytes),0,0,0,0,width,height);
   encoder.writeToTexture(depth.getColorTexture(),pixels.slice(bytes,bytes),0,0,0,0,width,height);
   uploaded=sequence;
  }
  carveInputs(encoder);
  var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
  var pipeline=RenderSystem.getCompiledPipelineNullable(PIPELINE);
  if(pipeline!=null){
   // Minecraft's own block-breaking texture for the current stage, drawn onto Zelda's floor.
   // Fetched before the pass opens: loading a texture issues its own commands.
   var cracks=Minecraft.getInstance().getTextureManager().getTexture(Identifier.withDefaultNamespace("textures/block/destroy_stage_"+Digging.miningStage()+".png"));
   try(var pass=encoder.createRenderPass(()->"Zelda frame colour and depth",target.getColorTextureView(),Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
    pass.setPipeline(pipeline);var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    pass.setUniform("ZeldaColor",color.getColorTextureView(),sampler);pass.setUniform("ZeldaDepth",depth.getColorTextureView(),sampler);
    pass.setUniform("Carve",carve.getColorTextureView(),sampler);pass.setUniform("Params",params.getColorTextureView(),sampler);
    pass.setUniform("Cracks",cracks.getTextureView(),sampler);
    pass.draw(3,1,0,0);
   }
  }else{
   // Shaders come from the generated resource pack; without it, fall back to no occlusion.
   color.blitAndBlendToTexture(target.getColorTextureView(),null);encoder.clearDepthTexture(target.getDepthTexture(),0.0);
  }
  encoder.submit();
  Guest.drawAll();
  return true;
 }
}
