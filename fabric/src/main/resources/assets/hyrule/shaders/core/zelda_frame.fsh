#version 330
#extension GL_ARB_separate_shader_objects : require
// Zelda's picture as the background, with its depth so blocks behind walls are hidden.
// Where the player has dug into a floor, Zelda's floor is removed so the hole and the
// blocks lining it show; where the player is mining a floor, Minecraft's cracks are
// drawn onto Zelda's surface.
uniform sampler2D ZeldaColor;
uniform sampler2D ZeldaDepth;
uniform sampler2D Carve;   // per block column: height of the removed floor, or -1000
uniform sampler2D Params;  // camera position, right, up, forward and lens; mined column
uniform sampler2D Cracks;  // Minecraft's block-breaking texture for the current stage
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
float param(int i){return texelFetch(Params,ivec2(i,0),0).r;}
float hash(vec3 p){return fract(sin(dot(p,vec3(12.9898,78.233,37.719)))*43758.5453);}
void main(){
 vec3 colour=texture(ZeldaColor,texCoord).rgb;
 float depth=texture(ZeldaDepth,texCoord).r;
 float near=param(11),far=param(15);
 // Reversed depth back to distance along the view axis, then to a world position.
 float distance=far*near/max(depth*(far-near)+near,1e-6);
 vec2 ndc=texCoord*2.0-1.0;
 vec3 position=vec3(param(0),param(1),param(2))
  +vec3(param(4),param(5),param(6))*(ndc.x*param(3)*distance)
  +vec3(param(8),param(9),param(10))*(ndc.y*param(7)*distance)
  +vec3(param(12),param(13),param(14))*distance;
 vec3 normal=normalize(cross(dFdx(position),dFdy(position)));
 ivec2 cell=ivec2(floor(position.xz));
 // Only floors: facing up, so Link and other things standing in a hole are kept.
 if(far>0.0&&depth>0.0&&abs(normal.y)>0.5&&cell.x>=0&&cell.y>=0&&cell.x<64&&cell.y<64){
  float height=texelFetch(Carve,cell,0).r;
  if(height>-999.0&&position.y<height+1.0&&position.y>ceil(height-0.001)-1.05){
   // Removed floor. Blocks lining the hole draw over this; any sliver they do not cover
   // (where smooth ground meets square blocks) reads as soil rather than a gap.
   vec3 grain=floor(position*16.0);
   colour=vec3(0.36,0.25,0.16)*(0.75+0.35*hash(grain));
   depth=0.0;
  }else if(param(19)>0.5&&cell==ivec2(int(param(16)),int(param(17)))&&abs(position.y-param(18))<1.0){
   // Vanilla blends cracks as twice the product, leaving mid-grey texels unchanged.
   vec4 crack=texture(Cracks,fract(position.xz));
   if(crack.a>0.1)colour=clamp(colour*crack.rgb*2.0,0.0,1.0);
  }
 }
 fragColor=vec4(colour,1.0);
 // Zelda's surfaces win ties with block faces lying exactly in them (buried blocks whose
 // tops are level with the floor), by sitting a fraction of a percent nearer.
 gl_FragDepth=min(depth*1.004,1.0);
}
