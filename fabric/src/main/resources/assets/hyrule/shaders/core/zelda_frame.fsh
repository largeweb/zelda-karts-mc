#version 330
#extension GL_ARB_separate_shader_objects : require
// Zelda's picture as the background, with its depth so blocks behind walls are hidden.
// Where the player has dug into a floor, Zelda's floor is removed so the Minecraft
// blocks that replaced it show instead.
uniform sampler2D ZeldaColor;
uniform sampler2D ZeldaDepth;
uniform sampler2D Carve;   // per block column: height of the removed floor, or -1000
uniform sampler2D Params;  // 16 floats: camera position, right, up, forward and lens
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
float param(int i){return texelFetch(Params,ivec2(i,0),0).r;}
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
 if(far>0.0&&depth>0.0&&cell.x>=0&&cell.y>=0&&cell.x<64&&cell.y<64){
  float height=texelFetch(Carve,cell,0).r;
  // Only the floor itself: near its recorded height and facing up, so Link and other
  // things standing in a hole are kept.
  if(height>-999.0&&position.y<height+0.6&&position.y>ceil(height-0.001)-1.05&&abs(normal.y)>0.5){
   colour=vec3(0.0);
   depth=0.0;
  }
 }
 fragColor=vec4(colour,1.0);
 // Zelda's surfaces win ties with block faces lying exactly in them (buried blocks whose
 // tops are level with the floor), by sitting a fraction of a percent nearer.
 gl_FragDepth=min(depth*1.004,1.0);
}
