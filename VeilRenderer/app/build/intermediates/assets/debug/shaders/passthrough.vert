attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uMVPMatrix;
uniform mat4 uTexMatrix;
varying vec2 vTextureCoord;

void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTextureCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}

