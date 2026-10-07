package local.composite.mixin;
import local.composite.Passthrough;
import local.composite.WorldFrame;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapMixin {
 @Inject(method="extract",at=@At("TAIL"))
 private void composite$light(LightmapRenderState state,float partialTick,CallbackInfo ci){
  if(!Passthrough.active())return;
  float light=WorldFrame.light();
  // The hidden Minecraft world's unrelated night cycle must not darken a
  // sunlit Zelda scene. Keep local block light, shading and status effects.
  state.skyFactor=light;state.skyLightColor=new org.joml.Vector3f(1,1,1);
  state.ambientColor=new org.joml.Vector3f(.08f+.16f*light);
  state.needsUpdate=true;
 }
}
