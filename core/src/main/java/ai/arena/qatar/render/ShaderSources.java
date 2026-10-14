package ai.arena.qatar.render;

/**
 * GLSL ES 3.00 sources for the world shader.
 *
 * <p>One shader draws the whole country: terrain, sea, building walls (with procedural windows),
 * roofs, asphalt and painted road markings. The surface type arrives per vertex in a packed attribute
 * and selects a branch, so a chunk is a single draw call instead of one per material -- which is what
 * makes a country of 190,000 buildings affordable on a phone.
 *
 * <p>Notes on the choices that matter:
 * <ul>
 *   <li><b>Fog is not decoration.</b> It hides the streaming boundary, so the player never sees the
 *       edge of the loaded world.</li>
 *   <li><b>Windows are procedural.</b> Banding the wall shader by real world height and distance gives
 *       every building glazing at no asset cost and no texture memory, and it scales to any building
 *       size because the bands are in metres.</li>
 *   <li><b>Materials drive colour.</b> Terrain tint is the compiled surface material id, so sand,
 *       sabkha, mangrove and urban pads read exactly as they were classified from the data.</li>
 * </ul>
 */
public final class ShaderSources {

    private ShaderSources() {
    }

    public static final String VERTEX = String.join("\n",
            "#version 300 es",
            "in vec3 a_position;",
            "in vec3 a_normal;",
            "in vec4 a_aux;            // x: surface type, y: u, z: v, w: tint (a plain float each)",
            "in float a_material;       // compiled material id, 0 when the surface has none",
            "uniform mat4 u_projView;",
            "uniform mat4 u_model;      // chunk-local metres -> national frame",
            "out vec3 v_position;",
            "out vec3 v_normal;",
            "out vec4 v_aux;",
            "out float v_material;",
            "void main() {",
            "    vec4 world = u_model * vec4(a_position, 1.0);",
            "    v_position = world.xyz;",
            "    v_normal = a_normal;",
            "    v_aux = a_aux;",
            "    v_material = a_material;",
            "    gl_Position = u_projView * world;",
            "}");

    public static final String FRAGMENT = String.join("\n",
            "#version 300 es",
            "precision highp float;",
            "in vec3 v_position;",
            "in vec3 v_normal;",
            "in vec4 v_aux;",
            "in float v_material;",
            "uniform vec3 u_camera;",
            "uniform vec3 u_sunDirection;     // points from the world towards the sun",
            "uniform vec3 u_sunColour;",
            "uniform vec3 u_fogColour;",
            "uniform float u_fogStart;",
            "uniform float u_fogEnd;",
            "uniform float u_time;",
            "out vec4 fragColour;",
            "",
            "const int SURFACE_LAND = 0;",
            "const int SURFACE_WATER = 1;",
            "const int SURFACE_WALL = 2;",
            "const int SURFACE_ROOF = 3;",
            "const int SURFACE_ROAD = 4;",
            "const int SURFACE_MARKING = 5;",
            "const int SURFACE_BODY = 6;",
            "",
            "// Mud, sand, gravel, mangrove, urban pad ... one entry per compiled material id.",
            "vec3 materialColour(int id) {",
            "    if (id == 1)  return vec3(0.20, 0.20, 0.21);   // dry asphalt",
            "    if (id == 2)  return vec3(0.24, 0.23, 0.22);   // dusty asphalt",
            "    if (id == 3)  return vec3(0.17, 0.18, 0.20);   // wet asphalt",
            "    if (id == 4)  return vec3(0.62, 0.61, 0.58);   // concrete pavers",
            "    if (id == 5)  return vec3(0.58, 0.52, 0.43);   // gravel and rock",
            "    if (id == 6)  return vec3(0.76, 0.68, 0.52);   // compact sand",
            "    if (id == 7)  return vec3(0.85, 0.76, 0.57);   // loose dune sand",
            "    if (id == 8)  return vec3(0.66, 0.60, 0.48);   // wet sand",
            "    if (id == 9)  return vec3(0.78, 0.77, 0.71);   // sabkha (salt flat)",
            "    if (id == 10) return vec3(0.42, 0.38, 0.32);   // tidal mud",
            "    if (id == 11) return vec3(0.24, 0.34, 0.22);   // mangrove and marsh",
            "    if (id == 12) return vec3(0.43, 0.47, 0.28);   // farm soil",
            "    if (id == 13) return vec3(0.66, 0.65, 0.62);   // urban pad",
            "    if (id == 14) return vec3(0.70, 0.69, 0.65);   // reclaimed land",
            "    if (id == 15) return vec3(0.35, 0.60, 0.65);   // shallow water",
            "    if (id == 16) return vec3(0.10, 0.30, 0.45);   // open water",
            "    return vec3(0.70, 0.66, 0.58);",
            "}",
            "",
            "// The paint on a body the player is wearing or driving. One surface type, eleven colours:",
            "// a car is silver, a tyre is black, an aeroplane is white, a thobe is white cloth, and none of",
            "// it needs a texture file.",
            "vec3 bodyColour(int id) {",
            "    if (id == 1)  return vec3(0.72, 0.73, 0.75);   // car paint",
            "    if (id == 2)  return vec3(0.14, 0.18, 0.22);   // glass",
            "    if (id == 3)  return vec3(0.06, 0.06, 0.07);   // tyre",
            "    if (id == 4)  return vec3(0.88, 0.89, 0.90);   // aircraft white",
            "    if (id == 5)  return vec3(0.26, 0.30, 0.28);   // helicopter green",
            "    if (id == 6)  return vec3(0.86, 0.87, 0.88);   // boat hull",
            "    if (id == 7)  return vec3(0.52, 0.36, 0.20);   // teak deck",
            "    if (id == 8)  return vec3(0.93, 0.93, 0.94);   // cloth",
            "    if (id == 9)  return vec3(0.72, 0.55, 0.42);   // skin",
            "    if (id == 10) return vec3(0.55, 0.56, 0.58);   // steel",
            "    if (id == 11) return vec3(0.16, 0.16, 0.17);   // rotor blade",
            "    return vec3(0.60, 0.60, 0.60);",
            "}",
            "",
            "// Per-vertex shade: the meshers compute it on the CPU (render colour, tunnel, paint) and it",
            "// multiplies whatever base colour the surface chose. Water keeps its own meaning for this",
            "// channel -- how shallow it is -- and is not scaled by it.",
            "float tintFactor(float t) {",
            "    return clamp(t, 0.05, 2.0);",
            "}",
            "",
            "// A little procedural grain keeps flat-shaded terrain from looking like plastic.",
            "float grain(vec2 p) {",
            "    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);",
            "}",
            "",
            "void main() {",
            "    // The auxiliary floats arrive as floats. They were read as normalised bytes here, which",
            "    // made a wall (2.0) read as 510 -- straight past every branch -- and the material ids of",
            "    // the whole country fall to the default beige. Read them for what they are.",
            "    int surface = int(v_aux.x + 0.5);",
            "    int material = int(v_material + 0.5);",
            "    vec3 base;",
            "    float roughness = 0.85;",
            "    vec3 normal = normalize(v_normal);",
            "",
            "    if (surface == SURFACE_LAND) {",
            "        // A land square the compiled materials still call water is the tide line: wet sand.",
            "        base = materialColour(material == 15 || material == 16 ? 8 : material);",
            "        float n = grain(floor(v_aux.yz * 0.7)) * 0.12 - 0.06;",
            "        base += n;",
            "        // Steep faces expose bare rock: the desert's own shading tells you where the slopes are.",
            "        float slope = 1.0 - clamp(normal.y, 0.0, 1.0);",
            "        base = mix(base, vec3(0.52, 0.46, 0.38), slope * 0.7);",
            "        base *= tintFactor(v_aux.w);",
            "        roughness = 0.95;",
            "    } else if (surface == SURFACE_WATER) {",
            "        float shallow = v_aux.w;",
            "        vec3 deep = vec3(0.05, 0.22, 0.40);",
            "        vec3 shelf = vec3(0.16, 0.48, 0.55);",
            "        base = mix(deep, shelf, clamp(shallow, 0.0, 1.0));",
            "        // Two cheap travelling waves: enough motion to read as sea from a car or a plane.",
            "        float wave = sin(v_aux.y * 0.35 + u_time * 1.7) * 0.5",
            "                   + sin(v_aux.z * 0.22 - u_time * 1.1) * 0.5;",
            "        normal = normalize(vec3(wave * 0.06, 1.0, wave * 0.04));",
            "        roughness = 0.15;",
            "    } else if (surface == SURFACE_WALL) {",
            "        base = vec3(0.80, 0.76, 0.68);                       // limestone render",
            "        float height = v_aux.z;                              // metres above the base",
            "        float along = v_aux.y;                               // metres along the wall",
            "        float floorBand = floor(height / 3.4);",
            "        if (floorBand >= 1.0) {",
            "            float fx = fract(along / 3.6);",
            "            float fy = fract(height / 3.4);",
            "            bool glazing = fx > 0.16 && fx < 0.84 && fy > 0.30 && fy < 0.82;",
            "            if (glazing) {",
            "                base = vec3(0.20, 0.27, 0.32);               // glass, lit by the sky",
            "                roughness = 0.25;",
            "            } else {",
            "                base *= 0.96;                                // spandrel shading",
            "            }",
            "        }",
            "        base += grain(vec2(along, height) * 0.9) * 0.05 - 0.025;",
            "        base *= tintFactor(v_aux.w);                      // render colour per building",
            "        roughness = 0.7;",
            "    } else if (surface == SURFACE_ROOF) {",
            "        base = vec3(0.86, 0.83, 0.76);",
            "        base += grain(v_aux.yz * 0.5) * 0.06 - 0.03;",
            "        base *= tintFactor(v_aux.w);",
            "        roughness = 0.8;",
            "    } else if (surface == SURFACE_BODY) {",
            "        base = bodyColour(material) * tintFactor(v_aux.w);",
            "        roughness = (material == 1 || material == 2 || material == 4 || material == 6)",
            "                ? 0.30 : 0.85;",
            "    } else if (surface == SURFACE_MARKING) {",
            "        base = vec3(0.92, 0.90, 0.84) * tintFactor(v_aux.w);     // paint",
            "        roughness = 0.6;",
            "    } else {",
            "        base = vec3(0.19, 0.19, 0.20);                           // asphalt",
            "        base += grain(v_aux.yz * 0.8) * 0.05 - 0.025;",
            "        base *= tintFactor(v_aux.w);                             // tunnels read darker",
            "        roughness = 0.88;",
            "    }",
            "",
            "    // Direct sun plus a sky ambient, then a specular highlight for water and glass.",
            "    vec3 sun = normalize(u_sunDirection);",
            "    float lambert = clamp(dot(normal, sun), 0.0, 1.0);",
            "    vec3 sky = mix(vec3(0.30, 0.34, 0.40), vec3(0.62, 0.68, 0.78),",
            "                   clamp(normal.y * 0.5 + 0.5, 0.0, 1.0));",
            "    vec3 colour = base * (sky + u_sunColour * lambert * 0.95);",
            "    vec3 view = normalize(u_camera - v_position);",
            // 'half' is a reserved word in GLSL ES 3.00 -- reserving it for a future 16-bit float
            // type -- and a driver that honours the spec rejects the whole shader for using it. This
            // is what a strict phone driver did, and the reason the country never drew.
            "    vec3 halfway = normalize(sun + view);",
            "    float spec = pow(max(dot(normal, halfway), 0.0), mix(8.0, 90.0, 1.0 - roughness));",
            "    colour += u_sunColour * spec * (1.0 - roughness) * 0.9;",
            "",
            "    // Distance fog: the loaded world fades into the horizon, so streaming is invisible.",
            "    float distance = length(u_camera - v_position);",
            "    float fog = clamp((distance - u_fogStart) / max(1.0, u_fogEnd - u_fogStart), 0.0, 1.0);",
            "    fog = fog * fog * (3.0 - 2.0 * fog);",
            "    colour = mix(colour, u_fogColour, fog);",
            "",
            "    fragColour = vec4(colour, 1.0);",
            "}");

    /**
     * Sky: one screen-space quad, unprojected into a view direction per pixel.
     *
     * <p>Drawing the sky as direction rather than geometry means it can never be clipped, never needs a
     * far-plane far enough for a whole country, and costs one draw call with depth writes off.
     */
    public static final String SKY_VERTEX = String.join("\n",
            "#version 300 es",
            "in vec2 a_position;       // clip-space corner, -1..1",
            "out vec2 v_ndc;",
            "void main() {",
            "    v_ndc = a_position;",
            "    gl_Position = vec4(a_position, 1.0, 1.0);   // pinned to the far plane",
            "}");

    public static final String SKY_FRAGMENT = String.join("\n",
            "#version 300 es",
            "precision highp float;",
            "in vec2 v_ndc;",
            "uniform mat4 u_invProjView;",
            "uniform vec3 u_camera;",
            "uniform vec3 u_sunDirection;",
            "uniform vec3 u_fogColour;",
            "uniform vec3 u_zenithColour;",
            "out vec4 fragColour;",
            "void main() {",
            "    vec4 world = u_invProjView * vec4(v_ndc, 1.0, 1.0);",
            "    vec3 direction = normalize(world.xyz / world.w - u_camera);",
            "    float up = clamp(direction.y, 0.0, 1.0);",
            "    // Haze at the horizon, deep blue overhead: the horizon matches the fog colour exactly, so",
            "    // the streamed world dissolves into the sky instead of ending at a visible edge.",
            "    vec3 colour = mix(u_fogColour, u_zenithColour, pow(up, 0.6));",
            "    float sun = pow(max(dot(direction, normalize(u_sunDirection)), 0.0), 180.0);",
            "    colour += vec3(1.0, 0.95, 0.85) * sun * 0.8;",
            "    fragColour = vec4(colour, 1.0);",
            "}");

    // ------------------------------------------------------------------------------------------
    // Fallback toolchain demo (milestone 0): sky, ground plane and a solid-shaded cube. Kept because
    // it is the proof that a device's GL pipeline works even if the compiled country cannot be loaded,
    // and it is what the startup report points at when the world bundle is unplayable.
    // ------------------------------------------------------------------------------------------

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
