package ai.arena.qatar.render;

/**
 * GLSL sources for the PBR-lite material system.
 *
 * <p>Written in the same style as libGDX's own shaders (attribute/varying/gl_FragColor) so
 * {@code ShaderProgram} can emit the correct GLSL ES version header for both GLES 2.0 fallback and
 * GLES 3.0 contexts. Every program is compiled at boot with full log capture and an explicit
 * fallback material, so a driver quirk degrades visibly instead of rendering nothing.
 */
public final class ShaderSources {

    private ShaderSources() {
    }

    /** Terrain/ground: sun + sky ambient + biome tint + distance haze. */
    public static final String TERRAIN_VERT =
            "attribute vec3 a_position;\n"
                    + "attribute vec3 a_normal;\n"
                    + "attribute vec3 a_biome;\n"          // x = material blend, y = wetness, z = detail noise
                    + "uniform mat4 u_projView;\n"
                    + "uniform mat4 u_model;\n"
                    + "uniform vec3 u_cameraLocal;\n"
                    + "varying vec3 v_normal;\n"
                    + "varying vec3 v_world;\n"
                    + "varying vec3 v_biome;\n"
                    + "varying float v_distance;\n"
                    + "void main() {\n"
                    + "  vec4 world = u_model * vec4(a_position, 1.0);\n"
                    + "  v_world = world.xyz;\n"
                    + "  v_normal = normalize(mat3(u_model[0].xyz, u_model[1].xyz, u_model[2].xyz) * a_normal);\n"
                    + "  v_biome = a_biome;\n"
                    + "  v_distance = length(world.xyz - u_cameraLocal);\n"
                    + "  gl_Position = u_projView * world;\n"
                    + "}\n";

    public static final String TERRAIN_FRAG =
            "precision mediump float;\n"
                    + "uniform vec3 u_sunDirection;\n"
                    + "uniform vec3 u_sunColor;\n"
                    + "uniform vec3 u_skyColor;\n"
                    + "uniform vec3 u_fogColor;\n"
                    + "uniform float u_fogDensity;\n"
                    + "uniform float u_heatHaze;\n"
                    + "uniform vec3 u_cameraLocal;\n"
                    + "varying vec3 v_normal;\n"
                    + "varying vec3 v_world;\n"
                    + "varying vec3 v_biome;\n"
                    + "varying float v_distance;\n"
                    + "void main() {\n"
                    + "  vec3 n = normalize(v_normal);\n"
                    + "  vec3 base = v_biome;\n"
                    + "  float ndl = max(dot(n, normalize(u_sunDirection)), 0.0);\n"
                    + "  vec3 diffuse = base * (u_skyColor * 0.35 + u_sunColor * ndl);\n"
                    + "  vec3 view = normalize(u_cameraLocal - v_world);\n"
                    + "  vec3 half_v = normalize(view + normalize(u_sunDirection));\n"
                    + "  float spec = pow(max(dot(n, half_v), 0.0), 24.0) * 0.08;\n"
                    + "  float fog = 1.0 - exp(-v_distance * u_fogDensity);\n"
                    + "  vec3 color = mix(diffuse + vec3(spec), u_fogColor, clamp(fog, 0.0, 1.0));\n"
                    + "  gl_FragColor = vec4(color, 1.0);\n"
                    + "}\n";

    /** Water: Fresnel mix of sky and depth-tinted body colour, plus a wave normal wobble. */
    public static final String WATER_VERT =
            "attribute vec3 a_position;\n"
                    + "uniform mat4 u_projView;\n"
                    + "uniform mat4 u_model;\n"
                    + "uniform float u_time;\n"
                    + "uniform float u_waveScale;\n"
                    + "varying vec3 v_world;\n"
                    + "varying vec3 v_normal;\n"
                    + "varying float v_wave;\n"
                    + "void main() {\n"
                    + "  vec4 world = u_model * vec4(a_position, 1.0);\n"
                    + "  float w1 = sin(world.x * 0.11 + u_time * 0.9) * 0.5;\n"
                    + "  float w2 = sin(world.z * 0.17 - u_time * 1.37) * 0.5;\n"
                    + "  float w3 = sin((world.x + world.z) * 0.05 + u_time * 0.6) * 0.5;\n"
                    + "  float wave = (w1 + w2 + w3) * 0.08 * u_waveScale;\n"
                    + "  world.y += wave;\n"
                    + "  v_wave = wave;\n"
                    + "  v_normal = normalize(vec3(w1 * 0.10, 1.0, w2 * 0.10));\n"
                    + "  v_world = world.xyz;\n"
                    + "  gl_Position = u_projView * world;\n"
                    + "}\n";

    public static final String WATER_FRAG =
            "precision mediump float;\n"
                    + "uniform vec3 u_sunDirection;\n"
                    + "uniform vec3 u_sunColor;\n"
                    + "uniform vec3 u_skyColor;\n"
                    + "uniform vec3 u_fogColor;\n"
                    + "uniform vec3 u_shallowColor;\n"
                    + "uniform vec3 u_deepColor;\n"
                    + "uniform float u_fogDensity;\n"
                    + "uniform float u_depthBlend;\n"
                    + "uniform vec3 u_cameraLocal;\n"
                    + "varying vec3 v_world;\n"
                    + "varying vec3 v_normal;\n"
                    + "varying float v_wave;\n"
                    + "void main() {\n"
                    + "  vec3 n = normalize(v_normal);\n"
                    + "  vec3 view = normalize(u_cameraLocal - v_world);\n"
                    + "  float fres = pow(1.0 - clamp(dot(n, view), 0.0, 1.0), 3.0);\n"
                    + "  vec3 body = mix(u_deepColor, u_shallowColor, clamp(u_depthBlend, 0.0, 1.0));\n"
                    + "  vec3 half_v = normalize(view + normalize(u_sunDirection));\n"
                    + "  float glitter = pow(max(dot(n, half_v), 0.0), 90.0) * 0.9;\n"
                    + "  vec3 color = mix(body, u_skyColor, clamp(fres * 0.85, 0.0, 1.0))\n"
                    + "      + u_sunColor * glitter;\n"
                    + "  color += vec3(max(v_wave, 0.0) * 0.12);\n"
                    + "  float fog = 1.0 - exp(-length(u_cameraLocal - v_world) * u_fogDensity);\n"
                    + "  gl_FragColor = vec4(mix(color, u_fogColor, clamp(fog, 0.0, 1.0)), 1.0);\n"
                    + "}\n";

    /** Lit untextured solid for props/vehicles/buildings until material atlases land. */
    public static final String SOLID_VERT =
            "attribute vec3 a_position;\n"
                    + "attribute vec3 a_normal;\n"
                    + "uniform mat4 u_projView;\n"
                    + "uniform mat4 u_model;\n"
                    + "varying vec3 v_normal;\n"
                    + "varying vec3 v_world;\n"
                    + "void main() {\n"
                    + "  vec4 world = u_model * vec4(a_position, 1.0);\n"
                    + "  v_world = world.xyz;\n"
                    + "  v_normal = normalize(mat3(u_model[0].xyz, u_model[1].xyz, u_model[2].xyz) * a_normal);\n"
                    + "  gl_Position = u_projView * world;\n"
                    + "}\n";

    public static final String SOLID_FRAG =
            "precision mediump float;\n"
                    + "uniform vec3 u_baseColor;\n"
                    + "uniform vec3 u_sunDirection;\n"
                    + "uniform vec3 u_sunColor;\n"
                    + "uniform vec3 u_skyColor;\n"
                    + "uniform vec3 u_fogColor;\n"
                    + "uniform float u_fogDensity;\n"
                    + "uniform vec3 u_cameraLocal;\n"
                    + "uniform float u_roughness;\n"
                    + "uniform float u_metallic;\n"
                    + "varying vec3 v_normal;\n"
                    + "varying vec3 v_world;\n"
                    + "void main() {\n"
                    + "  vec3 n = normalize(v_normal);\n"
                    + "  vec3 l = normalize(u_sunDirection);\n"
                    + "  float ndl = max(dot(n, l), 0.0);\n"
                    + "  vec3 view = normalize(u_cameraLocal - v_world);\n"
                    + "  vec3 half_v = normalize(view + l);\n"
                    + "  float shininess = mix(8.0, 128.0, 1.0 - clamp(u_roughness, 0.0, 1.0));\n"
                    + "  float spec = pow(max(dot(n, half_v), 0.0), shininess) * mix(0.04, 0.5, 1.0 - u_roughness);\n"
                    + "  vec3 diffuse = u_baseColor * (u_skyColor * 0.30 + u_sunColor * ndl);\n"
                    + "  vec3 color = diffuse + vec3(spec) * (1.0 - u_metallic * 0.4);\n"
                    + "  float fog = 1.0 - exp(-length(u_cameraLocal - v_world) * u_fogDensity);\n"
                    + "  gl_FragColor = vec4(mix(color, u_fogColor, clamp(fog, 0.0, 1.0)), 1.0);\n"
                    + "}\n";
}
