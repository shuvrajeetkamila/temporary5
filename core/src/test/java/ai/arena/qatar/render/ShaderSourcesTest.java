package ai.arena.qatar.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shaders, checked without a GPU -- which is the only kind of checking this project can do locally,
 * and which is why the first device build failed.
 *
 * <p>The failure that produced this test: the world fragment shader declared a variable called
 * {@code half}, and {@code half} is a <b>reserved word</b> in GLSL ES 3.00, sitting in the spec's list of
 * names reserved for future use. A desktop GL driver ignores it; a phone driver that follows the spec
 * rejects the shader, the world program never links, and the player gets a screen with nothing on it.
 * Nothing in the repository could have caught that, because nothing ever read the shader sources.
 *
 * <p>So this reads them: the version line, the reserved words, the direction of the attribute plumbing
 * between a vertex and a fragment shader, the precision statements a fragment shader must carry, and
 * every function a shader calls. It is a lint, not a compiler -- but it is the lint that would have kept
 * the build off the phone for a day less.
 */
final class ShaderSourcesTest {

    /**
     * Reserved for future use in GLSL ES 3.00 (spec section 3.7). Declaring one of these is a compile
     * error, however sensible the name looks.
     */
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "common", "partition", "active", "asm", "class", "union", "enum", "typedef", "template",
            "this", "resource", "goto", "inline", "noinline", "public", "static", "extern", "external",
            "interface", "long", "short", "double", "half", "fixed", "unsigned", "superp", "input",
            "output", "hvec2", "hvec3", "hvec4", "dvec2", "dvec3", "dvec4", "fvec2", "fvec3", "fvec4",
            "sampler3D", "sampler3DRect", "sampler1D", "sampler1DShadow", "sampler2DShadow",
            "sampler2DRect", "sampler2DRectShadow", "sizeof", "cast", "namespace", "using"));

    /** Built-in functions and constructors, so a call to anything else has to be declared in the shader. */
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "abs", "acos", "all", "any", "asin", "atan", "ceil", "clamp", "cos", "cosh", "cross",
            "degrees", "distance", "dot", "dFdx", "dFdy", "exp", "exp2", "faceforward", "floor",
            "fract", "fwidth", "inversesqrt", "length", "log", "log2", "matrixCompMult", "max", "min",
            "mix", "mod", "normalize", "pow", "radians", "reflect", "refract", "sign", "sin", "sinh",
            "smoothstep", "sqrt", "step", "tan", "tanh", "texture", "texture2D", "texture2DProj",
            "textureCube", "textureProj", "textureLod", "texelFetch", "not", "equal", "notEqual",
            "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual",
            "float", "int", "uint", "bool", "void", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
            "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "sampler2D", "samplerCube", "return",
            "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "discard",
            "main", "define", "version"));

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z_0-9]*)\\s*\\(");
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:in|out|uniform|attribute|varying|const)\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?"
                    + "([A-Za-z_0-9]+)\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*(?:\\[[^]]*])?\\s*;");

    /** Every shader in the project: the country's ES 3.00 pair, the sky pair, and the demo's ES 1.00 set. */
    private static Map<String, String> shaders() {
        Map<String, String> shaders = new LinkedHashMap<>();
        shaders.put("world vertex", ShaderSources.VERTEX);
        shaders.put("world fragment", ShaderSources.FRAGMENT);
        shaders.put("sky vertex", ShaderSources.SKY_VERTEX);
        shaders.put("sky fragment", ShaderSources.SKY_FRAGMENT);
        shaders.put("demo terrain vertex", ShaderSources.TERRAIN_VERT);
        shaders.put("demo terrain fragment", ShaderSources.TERRAIN_FRAG);
        shaders.put("demo water vertex", ShaderSources.WATER_VERT);
        shaders.put("demo water fragment", ShaderSources.WATER_FRAG);
        shaders.put("demo solid vertex", ShaderSources.SOLID_VERT);
        shaders.put("demo solid fragment", ShaderSources.SOLID_FRAG);
        return shaders;
    }

    @Test
    @DisplayName("no shader declares a name that GLSL reserves, whatever it looks like")
    void noReservedWords() {
        for (Map.Entry<String, String> shader : shaders().entrySet()) {
            String source = strip(shader.getValue());
            for (String identifier : identifiers(source)) {
                assertFalse(RESERVED.contains(identifier),
                        shader.getKey() + " uses the reserved word '" + identifier + "'. "
                                + "A driver that follows GLSL ES 3.00 rejects the whole shader for it.");
            }
        }
    }

    @Test
    @DisplayName("the country's shaders are 300 es and hold no 1.00 syntax")
    void modernShadersAreModern() {
        for (String name : new String[]{"world vertex", "world fragment", "sky vertex", "sky fragment"}) {
            String source = shaders().get(name);
            String first = source.split("\n", 2)[0].trim();
            assertEquals("#version 300 es", first, name + " does not begin with a version directive");

            String body = strip(source);
            for (String legacy : new String[]{"attribute ", "varying ", "gl_FragColor", "texture2D("}) {
                assertFalse(body.contains(legacy),
                        name + " uses GLSL ES 1.00 syntax ('" + legacy + "') in a 300 es shader. "
                                + "A 300 es shader must use in/out and fragColour.");
            }
        }
    }

    @Test
    @DisplayName("every fragment shader states a float precision, as ESSL requires")
    void fragmentShadersDeclarePrecision() {
        for (String name : new String[]{"world fragment", "sky fragment", "demo terrain fragment",
                "demo water fragment", "demo solid fragment"}) {
            String body = strip(shaders().get(name));
            assertTrue(Pattern.compile("precision\\s+(lowp|mediump|highp)\\s+float\\s*;").matcher(body).find(),
                    name + " declares no default float precision. A fragment shader without one "
                            + "does not compile on a compliant driver.");
        }
    }

    @Test
    @DisplayName("every function a shader calls is either built in or declared in that shader")
    void everyCallIsKnown() {
        for (Map.Entry<String, String> shader : shaders().entrySet()) {
            String source = strip(shader.getValue());
            Set<String> declared = declaredFunctions(source);
            Matcher matcher = CALL.matcher(source);
            while (matcher.find()) {
                String called = matcher.group(1);
                if (RESERVED.contains(called)) {
                    continue;
                }
                assertTrue(BUILTINS.contains(called) || declared.contains(called),
                        shader.getKey() + " calls '" + called + "()' which is neither a built-in nor "
                                + "declared in the shader. Declared here: " + declared);
            }
        }
    }

    @Test
    @DisplayName("what a vertex shader sends, the fragment shader receives, with matching types")
    void stagesAgree() {
        // Types must match exactly between stages or the program fails to link -- on the device, and not
        // before it. This is the check that stands in for the linker.
        assertStagesLink("world vertex", "world fragment", "in", "out", "in");
        assertStagesLink("sky vertex", "sky fragment", "in", "out", "in");
        assertStagesLink("demo terrain vertex", "demo terrain fragment", "attribute", "varying", "varying");
        assertStagesLink("demo water vertex", "demo water fragment", "attribute", "varying", "varying");
        assertStagesLink("demo solid vertex", "demo solid fragment", "attribute", "varying", "varying");
    }

    /** Vertex outputs must cover the fragment inputs, with the same type for each name. */
    private static void assertStagesLink(String vertexName, String fragmentName, String inputKeyword,
                                         String outputKeyword, String fragmentInputKeyword) {
        Map<String, String> inputs = declarations(shaders().get(vertexName), inputKeyword);
        Map<String, String> outputs = declarations(shaders().get(vertexName), outputKeyword);
        Map<String, String> fragmentInputs = declarations(shaders().get(fragmentName),
                fragmentInputKeyword);

        for (Map.Entry<String, String> wanted : fragmentInputs.entrySet()) {
            String provided = outputs.get(wanted.getKey());
            assertTrue(provided != null,
                    fragmentName + " reads '" + wanted.getKey() + "' but " + vertexName
                            + " never writes it: " + outputs);
            assertEquals(provided, wanted.getValue(),
                    fragmentName + " reads '" + wanted.getKey() + "' as " + wanted.getValue()
                            + " but " + vertexName + " writes it as " + provided);
        }
        assertFalse(inputs.isEmpty(), vertexName + " declares no inputs");
        assertFalse(fragmentInputs.isEmpty(), fragmentName + " reads nothing at all, which cannot be "
                + "right for a shader that paints something");
    }

    @Test
    @DisplayName("the demo shaders stay GLSL ES 1.00, because they are the fallback for old drivers")
    void demoShadersStayOld() {
        // The toolchain demo must compile on anything, including a device whose ES3 support is broken:
        // that is the whole point of keeping it. No version directive means 100, which every ES2 and
        // ES3 driver is required to accept.
        for (String name : new String[]{"demo terrain vertex", "demo terrain fragment", "demo water vertex",
                "demo water fragment", "demo solid vertex", "demo solid fragment"}) {
            String source = shaders().get(name);
            assertFalse(source.trim().startsWith("#version"),
                    name + " declares a version: the demo must stay on the default 100");
            for (String modern : new String[]{"in ", "out ", "fragColour", "texture("}) {
                assertFalse(strip(source).matches("(?s).*\\b" + Pattern.quote(modern) + ".*"),
                        name + " uses 300 es syntax ('" + modern + "') in a 100 shader");
            }
        }
    }

    @Test
    @DisplayName("the version line is the first thing in the file, with nothing before it")
    void versionIsFirst() {
        for (String name : new String[]{"world vertex", "world fragment", "sky vertex", "sky fragment"}) {
            String source = shaders().get(name);
            assertFalse(source.startsWith("\n") || source.startsWith(" ") || source.startsWith("\t"),
                    name + " begins with whitespace before #version, which some drivers reject");
            assertEquals(1, source.split("#version", -1).length - 1,
                    name + " has more than one version directive");
        }
    }

    /** Removes comments so a word in a comment is never mistaken for code. */
    private static String strip(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            int comment = line.indexOf("//");
            out.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return out.toString();
    }

    private static List<String> identifiers(String source) {
        List<String> found = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(source);
        while (matcher.find()) {
            String word = matcher.group();
            // Skip preprocessor lines entirely: #define/#version arguments are not identifiers of the shader.
            found.add(word);
        }
        return found;
    }

    private static Set<String> declaredFunctions(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "^\\s*(?:highp\\s+|mediump\\s+|lowp\\s+)?[A-Za-z_0-9]+\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*\\(",
                Pattern.MULTILINE).matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        names.removeAll(BUILTINS);
        return names;
    }

    private static Map<String, String> declarations(String source, String keyword) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String line : strip(source).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() <= keyword.length() || !trimmed.startsWith(keyword)) {
                continue;
            }
            if (!Character.isWhitespace(trimmed.charAt(keyword.length()))) {
                continue;                    // 'int surface' is not an 'in' declaration
            }
            Matcher matcher = DECLARATION.matcher(line);
            if (matcher.find()) {
                found.put(matcher.group(2), matcher.group(1));
            }
        }
        return found;
    }
}
