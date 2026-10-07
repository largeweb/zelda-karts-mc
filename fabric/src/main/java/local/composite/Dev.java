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
 private static int ticks,useTicks,attackTicks,moveTicks;
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
    mc.gui.setScreen(null);
   }else if(q.has("move")){
    if(moveKey!=null)moveKey.setDown(false);
    moveKey=switch(q.get("direction").getAsString()){case "back"->mc.options.keyDown;case "left"->mc.options.keyLeft;case "right"->mc.options.keyRight;case "jump"->mc.options.keyJump;default->mc.options.keyUp;};
    moveTicks=Math.clamp(q.get("move").getAsInt(),1,200);moveKey.setDown(true);
   }else if(q.has("attack")){
    attackTicks=Math.max(1,q.get("attack").getAsInt());mc.options.keyAttack.setDown(true);KeyMapping.click(mc.options.keyAttack.getDefaultKey());
   }else if(q.has("use")){
    useTicks=Math.max(1,q.get("use").getAsInt());mc.options.keyUse.setDown(true);KeyMapping.click(mc.options.keyUse.getDefaultKey());
   }else if(q.has("view")&&mc.player!=null){
    var v=q.getAsJsonArray("view");mc.player.setYRot(v.get(0).getAsFloat());mc.player.setXRot(v.get(1).getAsFloat());
   }else if(q.has("slot")&&mc.player!=null){
    mc.player.getInventory().setSelectedSlot(q.get("slot").getAsInt());
   }else if(q.has("camera")){
    mc.options.setCameraType(net.minecraft.client.CameraType.values()[q.get("camera").getAsInt()]);
   }else if(q.has("quit")){
    write("result.json",answer);mc.stop();return;
   }else if(q.has("zelda")){
    var key=q.get("zelda").getAsString();var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
    for(var entry:ZeldaItems.ALL)if(entry.key().equals(key))server.execute(()->{var sp=server.getPlayerList().getPlayer(uuid);if(sp!=null)sp.getInventory().add(ZeldaItems.stack(entry));});
   }else if(q.has("command")){
    var server=mc.getSingleplayerServer();
    if(server!=null)server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),q.get("command").getAsString()));
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
   if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit){
    s.addProperty("hit",hit.getType()+" "+hit.getBlockPos().toShortString()+" "+mc.level.getBlockState(hit.getBlockPos()).getBlock());
   }else s.addProperty("hit",String.valueOf(mc.hitResult==null?null:mc.hitResult.getType()));
  }
  return s;
 }
}
