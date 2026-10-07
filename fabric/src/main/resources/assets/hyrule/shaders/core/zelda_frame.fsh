#version 330
#extension GL_ARB_separate_shader_objects : require
// The native game's picture as the background, with its depth so blocks behind walls
// are hidden. Where the player has dug, the game's scenery is removed so the hole and
// the blocks lining it show. Around a dig, cells that the scenery runs through are
// drawn as soil up to that scenery, so the sides of a hole are solid right up to the
// ground's own surface. Where the player is mining, Minecraft's cracks are drawn on.
uniform sampler2D ZeldaColor;
uniform sampler2D ZeldaDepth;  // Minecraft depth; +2 where the pixel is not scene geometry
uniform sampler2D Carve;       // per block column: one bit per layer, set where dug out
uniform sampler2D Params;      // camera position, right, up, forward and lens; mined cell
uniform sampler2D Cracks;      // Minecraft's block-breaking texture for the current stage
uniform sampler2D Planes;      // per cell: the scenery surface through it (normal, offset)
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
float param(int i){return texelFetch(Params,ivec2(i,0),0).r;}
float hash(vec3 p){return fract(sin(dot(p,vec3(12.9898,78.233,37.719)))*43758.5453);}
bool inside(ivec3 c){return c.x>=0&&c.z>=0&&c.x<64&&c.z<64&&c.y>=0&&c.y<24;}
bool carved(ivec3 c){return mod(floor(texelFetch(Carve,c.xz,0).r/exp2(float(c.y))),2.0)>=1.0;}
// Minecraft-style ground: block-pixel grain, lit by which way the face points.
// Kind 1 is soil, 2 stone, 3 sand.
vec3 ground(vec3 at,vec3 normal,float kind){
 vec3 a=abs(normal);
 float light=a.y>=a.x&&a.y>=a.z?(normal.y>0.0?1.0:0.5):a.z>=a.x?0.8:0.6;
 vec3 base=kind>2.5?vec3(0.86,0.81,0.62):kind>1.5?vec3(0.50,0.50,0.50):vec3(0.53,0.38,0.26);
 return base*(0.8+0.3*hash(floor(at*16.0)))*light;
}
void main(){
 vec3 colour=texture(ZeldaColor,texCoord).rgb;
 float depth=texture(ZeldaDepth,texCoord).r;
 // Link, actors and effects are never removed, so they still show inside a hole.
 bool scenery=depth<1.5;
 if(!scenery)depth-=2.0;
 float near=param(11),far=param(15);
 // Reversed depth back to distance along the view axis, then to a position. Positions
 // are in blocks relative to the corner of the area the lookup textures cover.
 float distance=far*near/max(depth*(far-near)+near,1e-6);
 vec2 ndc=texCoord*2.0-1.0;
 vec3 ray=vec3(param(4),param(5),param(6))*(ndc.x*param(3))+vec3(param(8),param(9),param(10))*(ndc.y*param(7))+vec3(param(12),param(13),param(14));
 vec3 position=vec3(param(0),param(1)-param(20),param(2))+ray*distance;
 vec3 normal=normalize(cross(dFdx(position),dFdy(position)));
 if(far>0.0&&depth>0.0&&scenery){
  vec3 direction=normalize(ray);
  // The cell just behind the surface, as when choosing which cell to mine.
  vec3 origin=position+direction*0.05;
  ivec3 cell=ivec3(floor(origin));
  if(inside(cell)&&carved(cell)){
   // Dug out. Follow the line of sight on through the dug cells to whatever is behind.
   colour=ground(position,-direction,1.0);
   depth=0.0;
   vec3 stepSign=sign(direction);
   vec3 inverse=1.0/max(abs(direction),vec3(1e-5))*stepSign;
   vec3 next=(vec3(cell)+max(stepSign,vec3(0.0))-origin)*inverse;
   vec3 delta=abs(inverse);
   float travelled=0.0;
   for(int i=0;i<24;i++){
    vec3 face;
    if(next.x<next.y&&next.x<next.z){travelled=next.x;next.x+=delta.x;cell.x+=int(stepSign.x);face=vec3(-stepSign.x,0.0,0.0);}
    else if(next.y<next.z){travelled=next.y;next.y+=delta.y;cell.y+=int(stepSign.y);face=vec3(0.0,-stepSign.y,0.0);}
    else{travelled=next.z;next.z+=delta.z;cell.z+=int(stepSign.z);face=vec3(0.0,0.0,-stepSign.z);}
    if(!inside(cell))break;
    if(carved(cell))continue;
    vec4 plane=texelFetch(Planes,ivec2(cell.x,cell.y*64+cell.z),0);
    // Not a scenery cell: a real block (Minecraft draws it over this) or open air.
    if(plane.xyz==vec3(0.0))break;
    // The normal's length says what the cell is made of.
    float kind=floor(length(plane.xyz)+0.5);
    plane.xyz/=kind;
    float side=dot(plane.xyz,origin+direction*travelled-vec3(cell))-plane.w;
    float reached=-1.0;
    vec3 facing=face;
    if(side<0.0)reached=travelled;                 // straight into its solid part
    else{
     float slope=dot(plane.xyz,direction);
     float cross_=travelled-side/min(slope,-1e-5);
     if(slope<0.0&&cross_<min(next.x,min(next.y,next.z))){reached=cross_;facing=plane.xyz;} // onto its surface
    }
    if(reached<0.0)continue;                       // only through its open part: keep going
    vec3 at=origin+direction*reached;
    colour=ground(at,facing,kind);
    float along=distance+(reached+0.05)/length(ray);
    depth=clamp(near*(far/along-1.0)/(far-near),0.0,1.0);
    break;
   }
  }else if(inside(cell)&&param(19)>0.5&&cell==ivec3(int(param(16)),int(param(17)),int(param(18)))){
   // Texture the crack across the face being looked at: the two axes the surface spans.
   vec3 a=abs(normal);
   vec2 uv=a.y>=a.x&&a.y>=a.z?position.xz:a.x>=a.z?position.zy:position.xy;
   // Vanilla blends cracks as twice the product, leaving mid-grey texels unchanged.
   vec4 crack=texture(Cracks,fract(uv));
   if(crack.a>0.1)colour=clamp(colour*crack.rgb*2.0,0.0,1.0);
  }
 }
 fragColor=vec4(colour,1.0);
 // The game's surfaces win ties with block faces lying exactly in them (buried blocks
 // whose tops are level with the floor), by sitting a fraction of a percent nearer.
 gl_FragDepth=min(depth*1.004,1.0);
}
