package local.composite;
import java.nio.file.*;
import com.google.gson.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
/** Local test channel: status out, one command in. Only active when the launcher wrote a <shm>.dev marker naming a directory. */
public final class Dev {
 private static final Path ROOT=root();
 private static Path root(){
  try{var marker=Path.of(System.getProperty("composite.shm","")+".dev");return Files.isRegularFile(marker)?Path.of(Files.readString(marker).strip()):null;}
  catch(Exception e){return null;}
 }
 private static final Gson JSON=new Gson();
 public static boolean enabled(){return ROOT!=null;}
 /** One-shot instruction for the title screen, for testing world creation: startup.json. */
 public static boolean startup(Minecraft mc,net.minecraft.client.gui.screens.Screen title){
  try{
   var file=ROOT.resolve("startup.json");
   if(!Files.isRegularFile(file))return false;
   var q=JsonParser.parseString(Files.readString(file)).getAsJsonObject();Files.delete(file);
   if(q.has("open"))mc.createWorldOpenFlows().openWorld(q.get("open").getAsString(),()->mc.gui.setScreen(title));
   else if(q.has("screen"))net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(mc,()->mc.gui.setScreen(title));
   else for(var game:Games.ALL)if(game.id().equals(q.get("game").getAsString()))
    WorldCreator.create(mc,title,game,q.get("create").getAsString(),q.get("create").getAsString(),1,true);
   return true;
  }catch(Exception e){System.err.println("Dev startup: "+e);return true;}
 }
 private static int ticks,useTicks,attackTicks,moveTicks,gotoTicks,digTicks;
 private static double gotoX,gotoZ;
 private static net.minecraft.core.BlockPos digAt;
 private static net.minecraft.core.BlockPos block(JsonObject q,String key){var v=q.getAsJsonArray(key);return new net.minecraft.core.BlockPos(v.get(0).getAsInt(),v.get(1).getAsInt(),v.get(2).getAsInt());}
 private static void face(Minecraft mc,double x,double y,double z){
  var eye=mc.player.getEyePosition();double dx=x-eye.x,dy=y-eye.y,dz=z-eye.z;
  mc.player.setYRot((float)Math.toDegrees(Math.atan2(-dx,dz)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
 }
 /** Walking to a spot and mining a block take many ticks; the agent starts them and watches the status. */
 private static void longActions(Minecraft mc){
  if(mc.player==null){gotoTicks=digTicks=0;return;}
  if(gotoTicks>0){
   double dx=gotoX-mc.player.getX(),dz=gotoZ-mc.player.getZ();
   if(--gotoTicks==0||Math.hypot(dx,dz)<.35){gotoTicks=0;mc.options.keyUp.setDown(false);}
   else{mc.player.setYRot((float)Math.toDegrees(Math.atan2(-dx,dz)));mc.options.keyUp.setDown(true);}
  }
  if(digTicks>0&&digAt!=null){
   if(--digTicks==0||mc.level.getBlockState(digAt).isAir()){digTicks=0;mc.gameMode.stopDestroyBlock();}
   else{face(mc,digAt.getX()+.5,digAt.getY()+.5,digAt.getZ()+.5);mc.gameMode.continueDestroyBlock(digAt,net.minecraft.core.Direction.UP);mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);}
  }
 }
 /** Puts the named item in the hand: selects its hotbar slot, or swaps it in from the pack. */
 private static boolean hold(Minecraft mc,String id){
  var bag=mc.player.getInventory();
  if(mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer(); // a server menu left open would swallow the swap
  for(int i=0;i<36;i++)if(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bag.getItem(i).getItem()).toString().equals(id)){
   if(i<9)bag.setSelectedSlot(i);
   else mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,i,bag.getSelectedSlot(),net.minecraft.world.inventory.ContainerInput.SWAP,mc.player);
   return true;
  }
  return false;
 }
 private static KeyMapping moveKey;
 private static void write(String file,JsonObject value)throws Exception{
  var temp=ROOT.resolve(file+".tmp");Files.writeString(temp,JSON.toJson(value));
  Files.move(temp,ROOT.resolve(file),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
 }
 public static void tick(Minecraft mc){
  if(ROOT==null||!Files.isDirectory(ROOT))return;
  try{
   if(moveTicks>0&&--moveTicks==0)moveKey.setDown(false);
   if(attackTicks>0&&--attackTicks==0)mc.options.keyAttack.setDown(false);
   if(useTicks>0&&--useTicks==0)mc.options.keyUse.setDown(false);
   longActions(mc);
   if(++ticks%10==0)write("status.json",status(mc));
   var request=ROOT.resolve("command.json");
   if(!Files.isRegularFile(request))return;
   var q=JsonParser.parseString(Files.readString(request)).getAsJsonObject();Files.delete(request);
   var answer=new JsonObject();answer.add("id",q.get("id"));
   if(q.has("screenshot")){
    net.minecraft.client.Screenshot.grab(ROOT.toFile(),q.get("screenshot").getAsString(),mc.gameRenderer.mainRenderTarget(),1,message->{});
   }else if(q.has("respawn")&&mc.player!=null){
    mc.player.respawn();mc.gui.setScreen(null);
   }else if(q.has("closeScreen")){
    if(mc.player!=null)mc.player.closeContainer();
    mc.gui.setScreen(null);
   }else if(q.has("move")){
    if(moveKey!=null)moveKey.setDown(false);
    moveKey=switch(q.get("direction").getAsString()){case "back"->mc.options.keyDown;case "left"->mc.options.keyLeft;case "right"->mc.options.keyRight;case "jump"->mc.options.keyJump;case "sneak"->mc.options.keyShift;default->mc.options.keyUp;};
    moveTicks=Math.clamp(q.get("move").getAsInt(),1,200);moveKey.setDown(true);
   }else if(q.has("attack")){
    attackTicks=Math.max(1,q.get("attack").getAsInt());mc.options.keyAttack.setDown(true);KeyMapping.click(mc.options.keyAttack.getDefaultKey());
   }else if(q.has("use")){
    useTicks=Math.max(1,q.get("use").getAsInt());mc.options.keyUse.setDown(true);KeyMapping.click(mc.options.keyUse.getDefaultKey());
   }else if(q.has("view")&&mc.player!=null){
    var v=q.getAsJsonArray("view");mc.player.setYRot(v.get(0).getAsFloat());mc.player.setXRot(v.get(1).getAsFloat());
   }else if(q.has("slot")&&mc.player!=null){
    mc.player.getInventory().setSelectedSlot(q.get("slot").getAsInt());
   }else if(q.has("hold")&&mc.player!=null){
    answer.addProperty("ok",hold(mc,q.get("hold").getAsString()));
   }else if(q.has("place")&&mc.player!=null){
    // As NativeBlocks does for the first block on Zelda ground: a click on the empty cell itself.
    var pos=block(q,"place");var hand=net.minecraft.world.InteractionHand.MAIN_HAND;
    face(mc,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5);
    answer.addProperty("ok",mc.gameMode.useItemOn(mc.player,hand,new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(pos.getX()+.5,pos.getY(),pos.getZ()+.5),net.minecraft.core.Direction.UP,pos,false)).consumesAction());
    mc.player.swing(hand,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);
   }else if(q.has("useOn")&&mc.player!=null){
    var pos=block(q,"useOn");var hand=net.minecraft.world.InteractionHand.MAIN_HAND;
    face(mc,pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
    answer.addProperty("ok",mc.gameMode.useItemOn(mc.player,hand,new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5),net.minecraft.core.Direction.UP,pos,false)).consumesAction());
    mc.player.swing(hand,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);
   }else if(q.has("pour")&&mc.player!=null){
    // Buckets aim along the view on the server, so the view is set to the top of the named block first.
    var pos=block(q,"pour");face(mc,pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
    answer.addProperty("ok",mc.gameMode.useItem(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction());
   }else if(q.has("goto")&&mc.player!=null){
    var v=q.getAsJsonArray("goto");gotoX=v.get(0).getAsDouble();gotoZ=v.get(1).getAsDouble();gotoTicks=q.has("ticks")?Math.clamp(q.get("ticks").getAsInt(),1,600):200;
   }else if(q.has("dig")&&mc.player!=null){
    digAt=block(q,"dig");digTicks=q.has("ticks")?Math.clamp(q.get("ticks").getAsInt(),1,600):200;
    mc.gameMode.startDestroyBlock(digAt,net.minecraft.core.Direction.UP);
   }else if(q.has("stop")){
    gotoTicks=digTicks=0;mc.options.keyUp.setDown(false);mc.gameMode.stopDestroyBlock();
   }else if(q.has("camera")){
    mc.options.setCameraType(net.minecraft.client.CameraType.values()[q.get("camera").getAsInt()]);
   }else if(q.has("quit")){
    write("result.json",answer);mc.stop();return;
   }else if(q.has("zelda")){
    var key=q.get("zelda").getAsString();var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
    if(server==null)mc.getConnection().sendCommand("zelda "+key);
    else for(var entry:ZeldaItems.ALL)if(entry.key().equals(key))server.execute(()->{var sp=server.getPlayerList().getPlayer(uuid);if(sp!=null)sp.getInventory().add(ZeldaItems.stack(entry));});
   }else if(q.has("disconnect")){
    mc.disconnectWithProgressScreen(); // leave the world or server, as from the pause menu
   }else if(q.has("open")&&mc.level==null){
    mc.createWorldOpenFlows().openWorld(q.get("open").getAsString(),()->{});
   }else if(q.has("command")){
    var server=mc.getSingleplayerServer();
    if(server!=null)server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),q.get("command").getAsString()));
    else if(mc.getConnection()!=null)mc.getConnection().sendCommand(q.get("command").getAsString()); // on a server: as the player
   }
   write("result.json",answer);
  }catch(Exception e){System.err.println("Dev channel: "+e);}
 }
 private static JsonObject status(Minecraft mc){
  var s=new JsonObject();
  s.addProperty("time",System.currentTimeMillis());s.addProperty("active",Passthrough.active());s.addProperty("interactive",Passthrough.interactive());
  s.addProperty("fps",mc.getFps());s.addProperty("screen",mc.gui.screen()==null?"":mc.gui.screen().getClass().getSimpleName());
  if(mc.player!=null){
   s.addProperty("dimension",mc.player.level().dimension().identifier().toString());
   s.addProperty("x",mc.player.getX());s.addProperty("y",mc.player.getY());s.addProperty("z",mc.player.getZ());
   s.addProperty("yaw",mc.player.getYRot());s.addProperty("pitch",mc.player.getXRot());
   s.addProperty("grounded",mc.player.onGround());s.addProperty("health",mc.player.getHealth());
   s.addProperty("item",mc.player.getMainHandItem().toString());s.addProperty("zeldaItem",ZeldaItems.held(mc));
   s.addProperty("rupees",mc.player.experienceLevel);
   s.addProperty("mode",mc.gameMode.getPlayerMode().getName());s.addProperty("food",mc.player.getFoodData().getFoodLevel());
   s.addProperty("busy",gotoTicks>0||digTicks>0);s.addProperty("server",mc.getSingleplayerServer()==null);
   var bag=new JsonObject();
   for(int i=0;i<36;i++){var stack=mc.player.getInventory().getItem(i);if(stack.isEmpty())continue;
    var id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();bag.addProperty(id,(bag.has(id)?bag.get(id).getAsInt():0)+stack.getCount());}
   s.add("inventory",bag);
   // Blocks around the player, for an agent that decides from state instead of from the picture.
   var near=new JsonArray();var at=mc.player.blockPosition();
   for(var pos:net.minecraft.core.BlockPos.betweenClosed(at.offset(-6,-3,-6),at.offset(6,3,6))){
    var state=mc.level.getBlockState(pos);if(state.isAir()||state.is(net.minecraft.world.level.block.Blocks.LIGHT)||near.size()>=400)continue;
    var entry=new JsonArray();entry.add(pos.getX());entry.add(pos.getY());entry.add(pos.getZ());
    entry.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());near.add(entry);
   }
   s.add("blocks",near);
   try{
    var field=net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");field.setAccessible(true);
    var lines=new JsonArray();var all=(java.util.List<?>)field.get(mc.gui.hud.getChat());
    for(int i=Math.min(all.size(),30)-1;i>=0;i--)lines.add(((net.minecraft.client.multiplayer.chat.GuiMessage)all.get(i)).content().getString());
    s.add("chat",lines);
   }catch(ReflectiveOperationException e){}
   if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit){
    s.addProperty("hit",hit.getType()+" "+hit.getBlockPos().toShortString()+" "+mc.level.getBlockState(hit.getBlockPos()).getBlock());
   }else s.addProperty("hit",String.valueOf(mc.hitResult==null?null:mc.hitResult.getType()));
  }
  return s;
 }
}
