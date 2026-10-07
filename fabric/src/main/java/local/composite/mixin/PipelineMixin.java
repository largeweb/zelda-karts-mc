package local.composite.mixin;
import java.util.Map;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import local.composite.WorldFrame;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Registers the frame compositor so Minecraft compiles its shaders with the rest. */
@Mixin(RenderPipelines.class)
public class PipelineMixin {
 @Shadow @Final private static Map<Identifier,RenderPipeline> OPTIONAL_PIPELINES_BY_LOCATION;
 @Inject(method="<clinit>",at=@At("TAIL"))
 private static void composite$pipeline(CallbackInfo ci){OPTIONAL_PIPELINES_BY_LOCATION.put(WorldFrame.PIPELINE.getLocation(),WorldFrame.PIPELINE);}
}
