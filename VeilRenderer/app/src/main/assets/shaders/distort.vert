attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vUv;

void main() {
    gl_Position = aPosition;
    vUv = aTexCoord;
}

