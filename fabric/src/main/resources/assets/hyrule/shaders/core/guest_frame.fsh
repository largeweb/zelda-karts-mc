#version 330
#extension GL_ARB_separate_shader_objects : require
// A guest game's picture laid over the host's: only where the guest drew something
// (Link and what he uses), and only where that is nearer than what is already there.
uniform sampler2D GuestColor;
uniform sampler2D GuestDepth;  // Minecraft depth, +2 on everything that is not scenery
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 float depth=texture(GuestDepth,texCoord).r;
 if(depth>=1.5)depth-=2.0;
 if(depth<=0.0)discard;   // nothing drawn here: sky
 fragColor=vec4(texture(GuestColor,texCoord).rgb,1.0);
 gl_FragDepth=depth;
}
