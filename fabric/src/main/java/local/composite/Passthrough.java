package local.composite;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import com.mojang.blaze3d.platform.InputConstants;
/** Uses the real LocalPlayer, inventory, commands and vanilla travel, not a surrogate player. */
public final class Passthrough {
 public static final boolean ENABLED=Boolean.getBoolean("composite.passthrough");
 private static LocalPlayer attachedPlayer;
 private static Shared shared;private static int epoch,tick,scene=-1,lastButtons;private static boolean attached,locked;
 private static boolean wasGrounded=true,lastGuiOpen;
 private static volatile long heartbeat;private static final double BASE=1024,SCALE=40;
 public static double origin(){return Math.max(0,scene)*1024.0;}
 public static boolean matchesFrame(WorldFrame.FrameCamera frame){
  if(!active()||frame==null||frame.valid()!=1||frame.epoch()!=epoch||frame.scene()!=scene||frame.fov()<20||frame.fov()>150)return false;
  for(float value:new float[]{frame.x(),frame.y(),frame.z(),frame.yaw(),frame.pitch(),frame.fov()})if(!Float.isFinite(value)||Math.abs(value)>100000)return false;
  return true;
 }
 public static void frameTelemetry(WorldFrame.FrameCamera frame,float requestedYaw){if(shared!=null){shared.set(1488,WorldFrame.sequence());shared.set(1492,frame.request());shared.f(1496,requestedYaw-frame.yaw());shared.f(1500,WorldFrame.light());}}
 public static Vec3 clipCamera(net.minecraft.client.Camera camera){
  var mc=Minecraft.getInstance();if(!active()||shared==null||mc.player==null||!camera.isDetached())return null;
  var eye=mc.player.getEyePosition().subtract(origin(),BASE,0).scale(SCALE);
  var end=camera.position().subtract(origin(),BASE,0).scale(SCALE);
  var r=shared.camera(epoch,(float)eye.x,(float)eye.y,(float)eye.z,(float)end.x,(float)end.y,(float)end.z);
  return r==null?null:new Vec3(r[0]/SCALE+origin(),r[1]/SCALE+BASE,r[2]/SCALE);
 }
 public static void camera(net.minecraft.client.Camera camera){
  if(!active()||shared==null)return;
  var pos=camera.position().subtract(origin(),BASE,0).scale(SCALE);
  ByteBuffer b=buffer(32);b.putInt(epoch).putFloat((float)pos.x).putFloat((float)pos.y).putFloat((float)pos.z).putFloat(camera.yRot()).putFloat(camera.xRot()).putFloat(camera.getFov()).putInt(1);shared.publish(1280,b);
 }
 private static boolean aiming;
 /** Link is aiming an item in first person and Zelda is drawing his arms. */
 public static boolean aiming(){return active()&&aiming;}
 /** Tells Zelda the far plane Minecraft renders with, so exported depth matches Minecraft's. */
 public static void depthFar(float far){WorldFrame.far=far;if(shared!=null)shared.f(60,far);}
 /** Hyrule has its own sky; Minecraft rain and snow would also settle on blocks lining dug ground. */
 static void calmWeather(net.minecraft.server.MinecraftServer server){
  server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"execute in composite:zelda run weather clear 1000000");
 }
 public static boolean interactive(){return active()&&!locked;}
 public static boolean active(){return ENABLED&&attached&&System.nanoTime()-heartbeat<1_000_000_000L;}
 static ByteBuffer buffer(int n){return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);}
 public static void tick(Minecraft mc,ByteBuffer p,Shared shm){
  if(!ENABLED)return;shared=shm;heartbeat=System.nanoTime();
  var player=mc.player;var server=mc.getSingleplayerServer();if(player==null||server==null)return;
  var state=shm.snapshot(512,100);if(state==null)return;
  locked=(state.getInt(0)&14)!=0;aiming=(state.getInt(0)&32)!=0;
  if(epoch!=p.getInt(0)||scene!=p.getInt(48)||attachedPlayer!=player){
   attachedPlayer=player;
   org.lwjgl.sdl.SDLVideo.SDL_SetWindowTitle(mc.getWindow().handle(),"Minecraft x Ocarina of Time");
   epoch=p.getInt(0);scene=p.getInt(48);attached=true;
   player.setPos(p.getFloat(20)/SCALE+origin(),BASE+p.getFloat(24)/SCALE,p.getFloat(28)/SCALE);
   player.setDeltaMovement(Vec3.ZERO);player.setOnGround(true);
   player.setYRot(state.getFloat(4));player.setXRot(15);
   var pos=player.position();var uuid=player.getUUID();
   server.execute(()->{server.setWorldAllowCommands(true);Passthrough.calmWeather(server);var sp=server.getPlayerList().getPlayer(uuid);if(sp!=null){var dimension=server.getLevel(NativeBlocks.DIMENSION);if(dimension!=null)sp.teleportTo(dimension,pos.x,pos.y,pos.z,Set.of(),player.getYRot(),player.getXRot(),false);else sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("Hyrule dimension missing: recreate the world with ./hyrule setup."));server.getPlayerList().sendPlayerPermissionLevel(sp);server.getCommands().sendCommands(sp);}});
  }
  NativeAvatar.tick(mc,shm,epoch,state);
  if(locked){player.setPos(p.getFloat(20)/SCALE+origin(),BASE+p.getFloat(24)/SCALE,p.getFloat(28)/SCALE);player.setDeltaMovement(Vec3.ZERO);}
  mc.options.pauseOnLostFocus=false;
  // Until the source has a matching bob transform, keep world registration stable.
  mc.options.bobView().set(false);
  shm.set(48,mc.getFps());
  shm.set(1504,mc.gameMode.getDestroyStage());shm.set(1508,mc.hitResult==null?-1:mc.hitResult.getType().ordinal());
  shm.set(1512,NativeBlocks.inDimension()?1:0);shm.set(1516,player.getMainHandItem().getCount());
  shm.set(40,(player.isFallFlying()?1:0)|(player.getAbilities().flying?2:0)|(mc.gui.screen()!=null?4:0)|(player.onGround()?8:0)|(player.isSwinging()?16:0)|(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA)?32:0));
  boolean guiOpen=mc.gui.screen()!=null,enter=InputConstants.isKeyDown(InputConstants.KEY_RETURN);
  int buttons=0;
  if(mc.gui.screen()==null){
   var o=mc.options;
   if(o.keyUp.isDown())buttons|=1;if(o.keyDown.isDown())buttons|=2;if(o.keyLeft.isDown())buttons|=4;if(o.keyRight.isDown())buttons|=8;
   if(o.keyJump.isDown())buttons|=16;if(o.keyAttack.isDown())buttons|=32;if(o.keyUse.isDown())buttons|=128;
   if(enter&&!lastGuiOpen)buttons|=1<<19;
   if(InputConstants.isKeyDown(InputConstants.KEY_BACKSPACE))buttons|=1<<20;
   if(InputConstants.isKeyDown(InputConstants.KEY_O))buttons|=1<<27;
   if(InputConstants.isKeyDown(InputConstants.KEY_TAB))buttons|=1<<22;
   if(InputConstants.isKeyDown(InputConstants.KEY_UP))buttons|=1<<23;if(InputConstants.isKeyDown(InputConstants.KEY_DOWN))buttons|=1<<24;
   if(InputConstants.isKeyDown(InputConstants.KEY_LEFT))buttons|=1<<25;if(InputConstants.isKeyDown(InputConstants.KEY_RIGHT))buttons|=1<<26;
  }
  lastGuiOpen=guiOpen;
  int pressed=buttons&~lastButtons;lastButtons=buttons;
  // The collision RPC can advance a tick after vanilla evaluated its jump edge.
  // Retry vanilla's own glide transition after ground state has been resolved.
  if((pressed&16)!=0&&!wasGrounded&&!locked&&!player.onGround()&&!player.getAbilities().flying&&!player.isFallFlying()){
   if(player.tryToStartFallFlying()){
    player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player,net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
   }
  }
  Vec3 feet=player.position().subtract(origin(),BASE,0).scale(SCALE),eye=feet.add(0,player.getEyeHeight()*SCALE,0);
  FloorSupport.tick(mc,shm,epoch);
  NativeBlocks.target(p);NativeCombat.tick(shm,epoch);ZeldaFire.tick(mc,shm,epoch);Digging.tick(mc,shm,epoch);
  wasGrounded=player.onGround();
  // Native Minecraft renders/mines every block; only nearby full cubes are mirrored
  // into Zelda for native arrows/actors. Partial shapes use vanilla player collision.
  ByteBuffer layer=buffer(208);layer.putInt(epoch).putInt(0).putInt(16).putInt(-1);
  int count=0;var center=player.blockPosition();
  if(NativeBlocks.inDimension())for(var bp:net.minecraft.core.BlockPos.betweenClosed(center.offset(-4,-3,-4),center.offset(4,4,4))){
   if(count==16)break;var bs=mc.level.getBlockState(bp);
   if(!bs.is(net.minecraft.world.level.block.Blocks.BARRIER)&&bs.isCollisionShapeFullBlock(mc.level,bp)){
    layer.putFloat((float)((bp.getX()-origin())*SCALE)).putFloat((float)((bp.getY()-BASE)*SCALE)).putFloat((float)(bp.getZ()*SCALE));count++;
   }
  }
  layer.putInt(4,count);layer.putInt(8,16-count);shm.publish(1024,layer);
  int event=0;
  ByteBuffer out=buffer(52);out.putInt(epoch).putInt(++tick).putFloat((float)feet.x).putFloat((float)feet.y).putFloat((float)feet.z).putFloat(player.getYRot()).putFloat(player.getXRot()).putInt(player.onGround()?1:0).putInt(0).putInt(event).putFloat(0).putFloat(0).putFloat(0);shm.publish(out);
  // Ordinary held items belong to Minecraft; empty hands use native A/B; a held
  // Zelda item sends attack and use to Link.
  if(!ZeldaItems.holding(mc)||guiOpen)buttons&=~(32|128);
  buttons|=NativeButtons.poll(mc);
  if(!guiOpen&&InputConstants.isKeyDown(InputConstants.KEY_LALT))buttons|=1<<18;
  ZeldaStatus.tick(mc,shm,epoch);
  ByteBuffer response=buffer(60);response.putInt(epoch).putInt(buttons).putInt(player.getInventory().getSelectedSlot()).putInt(mc.gui.screen()!=null?1:0).putInt(mc.options.getCameraType().ordinal());for(int i=0;i<9;i++)response.putInt(254);response.putInt(0);shm.publish(768,response);
 }
 public static boolean move(LocalPlayer player,Vec3 desired){
  if(!active())return false;if(locked)return true;
  var pos=player.position();
  shared.f(44,player.getBbHeight()*40);
  Vec3 nativeMove=((local.composite.mixin.EntityCollisionAccessor)player).composite$collide(desired);
  float[] r=shared.collide(epoch,(float)((pos.x-origin())*SCALE),(float)((pos.y-BASE)*SCALE),(float)(pos.z*SCALE),(float)(nativeMove.x*SCALE),(float)(nativeMove.y*SCALE),(float)(nativeMove.z*SCALE));
  if(r==null){player.setDeltaMovement(Vec3.ZERO);return true;}
  Vec3 actual=new Vec3(r[0]/SCALE,r[1]/SCALE,r[2]/SCALE);
  actual=net.minecraft.world.entity.Entity.collideBoundingBox(player,actual,player.getBoundingBox(),player.level(),Digging.solids(player));player.setPos(pos.add(actual));
  boolean y=Math.abs(actual.y-desired.y)>1e-5,x=Math.abs(actual.x-desired.x)>1e-5,z=Math.abs(actual.z-desired.z)>1e-5;
  player.moveDist+=(float)actual.horizontalDistance()*.6f;player.flyDist+=(float)actual.length()*.6f;
  player.horizontalCollision=x||z;player.verticalCollision=y;player.setOnGround(y&&desired.y<0);
  var velocity=player.getDeltaMovement();player.setDeltaMovement(x?0:velocity.x,y?0:velocity.y,z?0:velocity.z);
  return true;
 }
}
