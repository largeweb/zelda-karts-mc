package local.composite;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
/**
 * /spawnkart: asks which character, then puts that character's kart under the player
 * and sets them driving. Mario Kart 64 has one kart per character, so the character is
 * the only choice. On a Mario Kart track the kart belongs to the world's own engine;
 * in a Zelda world the Mario Kart engine is started as a guest that draws just the
 * kart and drives it over Zelda's ground.
 */
public final class Karts {
 private static final String[] CHARACTERS={"Mario","Luigi","Yoshi","Toad","Donkey Kong","Wario","Peach","Bowser"};
 private static final int MOUNT=254;
 private static boolean asking;private static int current=0,guestCharacter=-1;
 /** A new world, or its engines stopped: no kart is out. */
 static void reset(){current=0;guestCharacter=-1;asking=false;}
 private static boolean inZelda(){return Engine.game()==Games.OCARINA&&Passthrough.active();}
 /** The player is in a guest kart in a Zelda world: Link is hidden and the keys go to the kart. */
 public static boolean ridingGuest(){return Engine.hasKart()&&Guest.KART.riding();}
 private static final String NOWHERE="Karts need a Mario Kart track or an Ocarina of Time world.",
  MISSING="Karts in Hyrule need Mario Kart 64 installed: run ./hyrule mk64 ROM with your own ROM.";
 /** Why a kart cannot be had here, or null when it can. */
 private static String unavailable(){
  if(onTrack())return null;
  if(!inZelda())return NOWHERE;
  return Engine.kartGame()==null?MISSING:null;
 }
 private static void say(String text){var mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal(text));}
 private static boolean onTrack(){var game=Engine.game();return game!=null&&game.family().equals("mk64")&&Passthrough.active();}
 /** /spawnkart with no number: list the characters and wait for the answer in chat. */
 public static void ask(){
  Minecraft.getInstance().execute(()->{
   var problem=unavailable();if(problem!=null){say(problem);return;}
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
   var problem=unavailable();if(problem!=null){say(problem);return;}
   var player=Minecraft.getInstance().player;int character=number-1;
   var at=String.format(java.util.Locale.ROOT,"%f,%f,%f,%f",(player.getX()-Passthrough.origin())*40,(player.getY()-1024)*40,player.getZ()*40,player.getYRot());
   String keys="W accelerates, S brakes, A/D steer, Space hops and drifts, Shift gets off; right click beside it with an empty hand gets back on.";
   if(inZelda()){
    if(Engine.hasKart()&&character==guestCharacter){
     Guest.KART.request(character); // the engine is running with this character: the kart just comes to the player
     say(CHARACTERS[character]+"'s kart. "+keys);
     return;
    }
    // The kart engine loads one character's kart when it starts.
    guestCharacter=character;
    say("Bringing "+CHARACTERS[character]+"'s kart; it takes a few seconds to arrive. "+keys);
    try{Engine.startKart(Map.of("COMPOSITE_CHARACTER",Integer.toString(character),"COMPOSITE_KART_AT",at));}
    catch(Exception e){guestCharacter=-1;say("Could not start Mario Kart: "+e.getMessage());}
    return;
   }
   if(character==current){
    ZeldaStatus.request(character); // same character: the kart just comes to the player
    say(CHARACTERS[character]+"'s kart. W accelerates, S brakes, A/D steer, Space hops and drifts, Shift gets off.");
    return;
   }
   // The game loads a character's kart when it loads the track, so changing character
   // reloads the track with the kart placed where the player is standing.
   current=character;
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
