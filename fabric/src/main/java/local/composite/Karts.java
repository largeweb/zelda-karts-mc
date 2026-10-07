package local.composite;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
/**
 * /spawnkart on a Mario Kart track: asks which character, then puts that character's
 * kart under the player and sets them driving. Mario Kart 64 has one kart per
 * character, so the character is the only choice.
 */
public final class Karts {
 private static final String[] CHARACTERS={"Mario","Luigi","Yoshi","Toad","Donkey Kong","Wario","Peach","Bowser"};
 private static final int MOUNT=254;
 private static boolean asking;private static int current=0;
 private static void say(String text){var mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal(text));}
 private static boolean onTrack(){var game=Engine.game();return game!=null&&game.family().equals("mk64")&&Passthrough.active();}
 /** /spawnkart with no number: list the characters and wait for the answer in chat. */
 public static void ask(){
  Minecraft.getInstance().execute(()->{
   if(!onTrack()){say("Karts are only on Mario Kart tracks. Create a world with Map set to a Mario Kart 64 track.");return;}
   var list=new StringBuilder("Which character?");
   for(int i=0;i<CHARACTERS.length;i++)list.append("  ").append(i+1).append(" ").append(CHARACTERS[i]);
   say(list.toString());
   say("Type a number 1-8 in chat. Anything else cancels.");
   asking=true;
  });
 }
 /** /spawnkart <n>, or the number typed after the question. */
 public static void spawn(int number){
  Minecraft.getInstance().execute(()->{
   asking=false;
   if(!onTrack()){say("Karts are only on Mario Kart tracks.");return;}
   var player=Minecraft.getInstance().player;int character=number-1;
   if(character==current){
    ZeldaStatus.request(character); // same character: the kart just comes to the player
    say(CHARACTERS[character]+"'s kart. W accelerates, S brakes, A/D steer, Space hops and drifts, Shift gets off.");
    return;
   }
   // The game loads a character's kart when it loads the track, so changing character
   // reloads the track with the kart placed where the player is standing.
   current=character;
   var at=String.format(java.util.Locale.ROOT,"%f,%f,%f,%f",(player.getX()-Passthrough.origin())*40,(player.getY()-1024)*40,player.getZ()*40,player.getYRot());
   say("Bringing "+CHARACTERS[character]+"'s kart; the track reloads for a moment...");
   Engine.restart(Map.of("COMPOSITE_CHARACTER",Integer.toString(character),"COMPOSITE_KART_AT",at));
  });
 }
 /** A chat line typed while the question is open is the answer, not chat. */
 public static boolean answer(String text){
  if(!asking)return false;
  asking=false;
  try{
   int number=Integer.parseInt(text.trim());
   if(number>=1&&number<=CHARACTERS.length){spawn(number);return true;}
  }catch(NumberFormatException e){}
  say("Kart cancelled.");
  return true;
 }
 /** Sneak while riding gets off; handled by the game, this just tells the player how to get back on. */
 public static void mount(){ZeldaStatus.request(MOUNT);}
}
