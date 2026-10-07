package local.composite.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Entity.class)
public interface EntityCollisionAccessor {
 @Invoker("collide") Vec3 composite$collide(Vec3 movement);
}
