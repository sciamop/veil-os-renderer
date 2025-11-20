1. What you need to do

Pipeline should be:

Render your scene / camera passthrough normally into a texture

one texture per eye, or side-by-side in one big texture.

Draw a full-screen quad to the actual phone screen.

In the fragment shader for that quad, warp UVs with a radial function:

distortedUV = center + (uv - center) * (1 + k1*r² + k2*r⁴ + k3*r⁶)

Those k values are what you tweak to match the Quest 2 lenses.

2. Simple lens distortion model

We treat coordinates around the lens center:

p is a point in NDC (−1..1)

r² = x² + y²

factor = 1 + k1*r² + k2*r⁴ + k3*r⁶

p' = p * factor

Convert p' back to UV (0..1) and sample the source texture.

We also correct for aspect ratio (phone is tall).

3. Minimal full-screen distortion pass (GLES)

I’ll assume:

You already rendered your eye(s) into a texture uTex

You’re just drawing a full-screen quad to apply the warp.

For now, I’ll show single eye correction (you can run it twice or adapt for side-by-side).

Vertex shader (distort.vert)