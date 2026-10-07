package local.composite.mixin;
import local.composite.Passthrough;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ChunkSectionsToRender.class)
public class TerrainMixin {
 @Inject(method="renderLayers",at=@At("HEAD"),cancellable=true)
 private void composite$terrain(CallbackInfo ci){if(Passthrough.active()&&(!local.composite.NativeBlocks.inDimension()||!Passthrough.interactive()))ci.cancel();}
}
