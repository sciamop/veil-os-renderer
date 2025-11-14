#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uPrevTex;
uniform samplerExternalOES uCurrTex;
uniform float uInterpFactor;
varying vec2 vTextureCoord;

void main() {
    // Simple blend-based interpolation (can be upgraded to ML-based later)
    vec4 prevColor = texture2D(uPrevTex, vTextureCoord);
    vec4 currColor = texture2D(uCurrTex, vTextureCoord);
    
    // Blend between previous and current frame
    gl_FragColor = mix(prevColor, currColor, uInterpFactor);
}

