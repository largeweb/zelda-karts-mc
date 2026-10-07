package local.composite.mixin;
import local.composite.*;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Minecraft.class)
public class NativeUseMixin {
 @Inject(method="pick",at=@At("TAIL"))
 private void composite$pick(CallbackInfo ci){FloorSupport.hidePick((Minecraft)(Object)this);}
 @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
 private void composite$use(CallbackInfo ci){if(NativeButtons.click((Minecraft)(Object)this,false)||(Passthrough.active()&&!Passthrough.interactive())||ZeldaItems.holding((Minecraft)(Object)this)||NativeBlocks.useGround((Minecraft)(Object)this))ci.cancel();}
 @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
 private void composite$emptyAttack(CallbackInfoReturnable<Boolean> ci){if(NativeButtons.click((Minecraft)(Object)this,true)||ZeldaItems.holding((Minecraft)(Object)this))ci.setReturnValue(false);}
 @Inject(method="continueAttack",at=@At("HEAD"),cancellable=true)
 private void composite$emptyMine(boolean held,CallbackInfo ci){if(NativeButtons.empty((Minecraft)(Object)this)||ZeldaItems.holding((Minecraft)(Object)this))ci.cancel();}
 @Inject(method="startAttack",at=@At("RETURN"))
 private void composite$attack(CallbackInfoReturnable<Boolean> ci){NativeCombat.attack((Minecraft)(Object)this);}
}
