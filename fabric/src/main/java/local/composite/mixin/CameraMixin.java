package local.composite.mixin;
import local.composite.Passthrough;
import local.composite.WorldFrame;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Camera.class)
public abstract class CameraMixin {
 @Shadow protected abstract void setPosition(net.minecraft.world.phys.Vec3 position);
 @Shadow protected abstract void setRotation(float yaw,float pitch);
 @Shadow private float fov;
 @Shadow private float depthFar;
 @Shadow private void setupPerspective(float near,float far,float fov,float w,float h){throw new AssertionError();}
 @Shadow private org.joml.Matrix4f createProjectionMatrixForCulling(){throw new AssertionError();}
 @Shadow private void prepareCullFrustum(org.joml.Matrix4fc view,org.joml.Matrix4f projection,net.minecraft.world.phys.Vec3 position){throw new AssertionError();}
 @Inject(method="update",at=@At("TAIL"))
 private void composite$camera(CallbackInfo ci){
  var camera=(Camera)(Object)this;var position=Passthrough.clipCamera(camera);if(position!=null)setPosition(position);
  // Send the fresh desired camera first; never feed the older displayed pose back.
  Passthrough.camera(camera);float requestedYaw=camera.yRot();
  var frame=WorldFrame.prepare();
  if(Passthrough.matchesFrame(frame)){
   setPosition(new net.minecraft.world.phys.Vec3(frame.x()/40.0+Passthrough.origin(),1024+frame.y()/40.0,frame.z()/40.0));
   setRotation(frame.yaw(),frame.pitch());fov=frame.fov();
   var window=net.minecraft.client.Minecraft.getInstance().getWindow();
   setupPerspective(WorldFrame.NEAR,depthFar,fov,window.getWidth(),window.getHeight());
   Passthrough.depthFar(depthFar);
   prepareCullFrustum(camera.getViewRotationMatrix(new org.joml.Matrix4f()),createProjectionMatrixForCulling(),camera.position());
   Passthrough.frameTelemetry(frame,requestedYaw);
  }
 }
}
