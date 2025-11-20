precision mediump float;

uniform sampler2D uSceneTexture;
uniform vec3 uLensCoeffs;
uniform vec2 uLensCenterLeft;
uniform vec2 uLensCenterRight;
uniform float uEyeAspect;

varying vec2 vUv;

vec2 distort(vec2 eyeUv, vec2 center, vec3 coeffs, float aspect) {
    vec2 p = eyeUv - center;
    p.y *= aspect;
    float r2 = dot(p, p);
    float factor = 1.0 + coeffs.x * r2 + coeffs.y * r2 * r2 + coeffs.z * r2 * r2 * r2;
    vec2 warped = center + vec2(p.x, p.y / aspect) * factor;
    return warped;
}

void main() {
    bool leftEye = vUv.x < 0.5;
    vec2 eyeUv = leftEye
        ? vec2(vUv.x * 2.0, vUv.y)
        : vec2((vUv.x - 0.5) * 2.0, vUv.y);
    vec2 center = leftEye ? uLensCenterLeft : uLensCenterRight;
    vec2 warped = distort(eyeUv, center, uLensCoeffs, uEyeAspect);
    
    if (warped.x < 0.0 || warped.x > 1.0 || warped.y < 0.0 || warped.y > 1.0) {
        gl_FragColor = vec4(0.0);
        return;
    }
    
    vec2 sampleUv = leftEye
        ? vec2(warped.x * 0.5, 1.0 - warped.y)
        : vec2(0.5 + warped.x * 0.5, 1.0 - warped.y);
        
    gl_FragColor = texture2D(uSceneTexture, sampleUv);
}

