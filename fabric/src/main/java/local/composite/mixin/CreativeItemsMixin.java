package local.composite.mixin;
import local.composite.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(CreativeModeTab.class)
public class CreativeItemsMixin {
 @Inject(method="buildContents",at=@At("TAIL"))
 private void composite$items(CreativeModeTab.ItemDisplayParameters parameters,CallbackInfo ci){
  if(!Passthrough.ENABLED)return;
  var tab=(CreativeModeTab)(Object)this;
  var id=BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
  if(id==null||!id.toString().equals("minecraft:tools_and_utilities"))return;
  for(var entry:ZeldaItems.ALL){
   tab.getDisplayItems().add(ZeldaItems.stack(entry));
   tab.getSearchTabDisplayItems().add(ZeldaItems.stack(entry));
  }
 }
}
