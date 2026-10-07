package local.composite;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
/** Ocarina of Time items as Minecraft inventory items. Holding one puts it in Link's hand. */
public final class ZeldaItems {
 public record Entry(int id,String key,String name){}
 public static final int NONE=255;
 private static final String TAG="hyrule_item";
 public static final List<Entry> ALL=load();
 private static List<Entry> load(){
  var entries=new ArrayList<Entry>();
  try(var in=new InputStreamReader(ZeldaItems.class.getResourceAsStream("/hyrule_items.json"),StandardCharsets.UTF_8)){
   for(var element:JsonParser.parseReader(in).getAsJsonArray()){
    var o=element.getAsJsonObject();entries.add(new Entry(o.get("id").getAsInt(),o.get("key").getAsString(),o.get("name").getAsString()));
   }
  }catch(Exception e){throw new IllegalStateException("hyrule_items.json is missing or invalid",e);}
  return List.copyOf(entries);
 }
 public static ItemStack stack(Entry entry){
  var stack=new ItemStack(Items.PAPER);var tag=new CompoundTag();tag.putInt(TAG,entry.id());
  stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
  stack.set(DataComponents.ITEM_NAME,Component.literal(entry.name()));
  stack.set(DataComponents.ITEM_MODEL,Identifier.fromNamespaceAndPath("hyrule",entry.key()));
  stack.set(DataComponents.MAX_STACK_SIZE,1);
  return stack;
 }
 /** The Zelda item id carried by this stack, or -1 for an ordinary Minecraft item. */
 public static int id(ItemStack stack){
  var data=stack.get(DataComponents.CUSTOM_DATA);
  return data==null?-1:data.copyTag().getIntOr(TAG,-1);
 }
 public static boolean holding(Minecraft mc){return Passthrough.active()&&mc.player!=null&&id(mc.player.getMainHandItem())>=0;}
 public static int held(Minecraft mc){
  int id=mc.player==null?-1:id(mc.player.getMainHandItem());
  return id<0?NONE:id;
 }
}
