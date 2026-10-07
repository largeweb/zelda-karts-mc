package local.composite.mixin;
import local.composite.NativeCombat;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerExplosion.class)
public class ExplosionMixin {
 @Inject(method="explode",at=@At("HEAD"))
 private void composite$blast(CallbackInfoReturnable<Integer> ci){NativeCombat.explosion((ServerExplosion)(Object)this);}
}
