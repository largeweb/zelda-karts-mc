#version 330
#extension GL_ARB_separate_shader_objects : require
// Zelda's picture as the background, with its depth so blocks behind walls are hidden.
// Where the player has dug into the scenery, Zelda's scenery is removed so the hole and
// the blocks lining it show; where the player is mining, Minecraft's cracks are drawn
// onto Zelda's surface.
uniform sampler2D ZeldaColor;
uniform sampler2D ZeldaDepth;  // Minecraft depth; +2 where the pixel is not scene geometry
uniform sampler2D Carve;       // per block column: one bit per layer, set where dug out
uniform sampler2D Params;      // camera position, right, up, forward and lens; mined cell
uniform sampler2D Cracks;      // Minecraft's block-breaking texture for the current stage
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
float param(int i){return texelFetch(Params,ivec2(i,0),0).r;}
float hash(vec3 p){return fract(sin(dot(p,vec3(12.9898,78.233,37.719)))*43758.5453);}
void main(){
 vec3 colour=texture(ZeldaColor,texCoord).rgb;
 float depth=texture(ZeldaDepth,texCoord).r;
 // Link, actors and effects are never removed, so they still show inside a hole.
 bool scenery=depth<1.5;
 if(!scenery)depth-=2.0;
 float near=param(11),far=param(15);
 // Reversed depth back to distance along the view axis, then to a world position.
 float distance=far*near/max(depth*(far-near)+near,1e-6);
 vec2 ndc=texCoord*2.0-1.0;
 vec3 ray=vec3(param(4),param(5),param(6))*(ndc.x*param(3))+vec3(param(8),param(9),param(10))*(ndc.y*param(7))+vec3(param(12),param(13),param(14));
 vec3 position=vec3(param(0),param(1),param(2))+ray*distance;
 vec3 normal=normalize(cross(dFdx(position),dFdy(position)));
 if(far>0.0&&depth>0.0&&scenery){
  // The cell just behind the surface, as when choosing which cell to mine.
  vec3 inside=position+normalize(ray)*0.05;
  ivec3 cell=ivec3(floor(vec3(inside.x,inside.y-param(20),inside.z)));
  if(cell.x>=0&&cell.z>=0&&cell.x<64&&cell.z<64&&cell.y>=0&&cell.y<24){
   float layers=texelFetch(Carve,cell.xz,0).r;
   if(mod(floor(layers/exp2(float(cell.y))),2.0)>=1.0){
    // Dug out. Blocks lining the hole draw over this; any sliver they do not cover
    // (where smooth scenery meets square blocks) reads as soil rather than a gap.
    colour=vec3(0.36,0.25,0.16)*(0.75+0.35*hash(floor(position*16.0)));
    depth=0.0;
   }else if(param(19)>0.5&&cell==ivec3(int(param(16)),int(param(17)),int(param(18)))){
    // Texture the crack across the face being looked at: the two axes the surface spans.
    vec3 a=abs(normal);
    vec2 uv=a.y>=a.x&&a.y>=a.z?position.xz:a.x>=a.z?position.zy:position.xy;
    // Vanilla blends cracks as twice the product, leaving mid-grey texels unchanged.
    vec4 crack=texture(Cracks,fract(uv));
    if(crack.a>0.1)colour=clamp(colour*crack.rgb*2.0,0.0,1.0);
   }
  }
 }
 fragColor=vec4(colour,1.0);
 // Zelda's surfaces win ties with block faces lying exactly in them (buried blocks whose
 // tops are level with the floor), by sitting a fraction of a percent nearer.
 gl_FragDepth=min(depth*1.004,1.0);
}
