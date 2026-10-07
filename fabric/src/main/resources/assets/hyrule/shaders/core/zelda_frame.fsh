#version 330
#extension GL_ARB_separate_shader_objects : require
// Zelda's picture as the background, with its depth so blocks behind walls are hidden.
uniform sampler2D ZeldaColor;
uniform sampler2D ZeldaDepth;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 fragColor=vec4(texture(ZeldaColor,texCoord).rgb,1.0);
 gl_FragDepth=texture(ZeldaDepth,texCoord).r;
}
