package local.composite.mixin;
import local.composite.NativeCombat;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Projectile.class)
public class ProjectileMixin {
 @Inject(method="applyOnProjectileSpawned",at=@At("TAIL"))
 private void composite$projectile(CallbackInfo ci){NativeCombat.projectile((Projectile)(Object)this);}
}
