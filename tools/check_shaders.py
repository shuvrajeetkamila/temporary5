#!/usr/bin/env python3
"""Read the shaders out of `ShaderSources.java` and check the things a device taught us the hard way.

Every shader in this project lives in one Java file as concatenated string literals, which means the
first thing that ever *read* them was a GL driver -- on a phone, after a ten minute build, in front of
a person holding a screenshot. Three failures reached a device that way:

  * `half`, reserved in GLSL ES 3.00, used as a variable name: the driver rejected the program, the
    renderer bound program 0, and the game died on a blue screen;
  * `uniform vec3 u_cameraLocal` with no qualifier, declared in a vertex and a fragment stage whose
    defaults differ: a Mali driver refused to link it, and the fallback scene failed with it;
  * a version directive that is not the first line, which drivers disagree about.

The same rules are enforced as JUnit tests (`ShaderSourcesTest`), and those are the ones that decide
whether a build is good. This exists because the JUnit tests need a JVM and this sandbox has no Java
compiler: a check that cannot run while the code is being written is a check that runs too late.

Run: python3 tools/check_shaders.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / "core" / "src" / "main" / "java" / "ai" / "arena" / "qatar" / "render" / "ShaderSources.java"

# Reserved in both GLSL ES 1.00 and 3.00. (`attribute`, `varying`, `texture2D` and `gl_FragColor` are
# legal in 1.00 and merely old in 3.00, so they are checked by the family rule below, not here.)
RESERVED = {
    "common", "partition", "active", "asm", "class", "union", "enum", "typedef", "template", "this",
    "resource", "goto", "inline", "noinline", "public", "static", "extern", "external", "interface",
    "long", "short", "double", "half", "fixed", "unsigned", "superp", "input", "output", "hvec2",
    "hvec3", "hvec4", "dvec2", "dvec3", "dvec4", "fvec2", "fvec3", "fvec4", "sampler3D",
    "sampler3DRect", "sampler1D", "sampler1DShadow", "sampler2DShadow", "sampler2DRect",
    "sampler2DRectShadow", "sizeof", "cast", "namespace", "using",
}

# The pairs whose boundary is a real stage boundary: (vertex, fragment, (out keyword, in keyword), label).
PAIRS = [
    ("VERTEX", "FRAGMENT", ("out", "in"), "world"),
    ("SKY_VERTEX", "SKY_FRAGMENT", ("out", "in"), "sky"),
    ("TERRAIN_VERT", "TERRAIN_FRAG", ("varying", "varying"), "demo terrain"),
    ("WATER_VERT", "WATER_FRAG", ("varying", "varying"), "demo water"),
    ("SOLID_VERT", "SOLID_FRAG", ("varying", "varying"), "demo solid"),
]

ES3 = ["VERTEX", "FRAGMENT", "SKY_VERTEX", "SKY_FRAGMENT"]
FRAGMENTS = ["FRAGMENT", "SKY_FRAGMENT", "TERRAIN_FRAG", "WATER_FRAG", "SOLID_FRAG"]
ALL = ES3 + [name for _, name, _, _ in PAIRS] + [name for name, _, _, _ in PAIRS]

DECLARATION = re.compile(
    r"^\s*(?P<keyword>in|out|uniform|attribute|varying|const)\s+"
    r"(?P<precision>lowp\s+|mediump\s+|highp\s+)?"
    r"(?P<type>[A-Za-z_0-9]+)\s+(?P<name>[A-Za-z_][A-Za-z_0-9]*)\s*(?:\[[^]]*])?\s*;",
    re.MULTILINE,
)


def literal(text: str) -> str:
    return text.replace('\\n', '\n').replace('\\"', '"').replace('\\\\', '\\')


def extract(source: str) -> dict[str, str]:
    """One entry per `public static final String NAME = ...;`, literals resolved and joined."""
    shaders: dict[str, str] = {}
    for match in re.finditer(r"public static final String (\w+)\s*=\s*(.*?);\n", source, re.S):
        name, body = match.group(1), match.group(2)
        parts = [literal(part) for part in re.findall(r'"((?:[^"\\]|\\.)*)"', body)]
        if not parts:
            continue
        if "String.join" in body:
            shaders[name] = parts[0].join(parts[1:])      # the first literal is the separator
        else:
            shaders[name] = "".join(parts)
    return shaders


def strip_comments(source: str) -> str:
    return re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", source, flags=re.S))


def declarations(source: str, keyword: str) -> dict[str, tuple[str, str | None]]:
    found: dict[str, tuple[str, str | None]] = {}
    for match in DECLARATION.finditer(strip_comments(source)):
        if match.group("keyword") == keyword:
            precision = match.group("precision")
            found[match.group("name")] = (match.group("type"), precision.strip() if precision else None)
    return found


def identifiers(source: str) -> list[str]:
    body = re.sub(r"#[^\n]*", "", strip_comments(source))
    return re.findall(r"[A-Za-z_][A-Za-z_0-9]*", body)


def check(shaders: dict[str, str], complete: bool = True) -> list[str]:
    """Every shader problem, with `complete` false for the small samples in the self-test."""
    problems: list[str] = []
    if complete:
        # An extractor that returns nothing passes every check below, so it is checked first.
        missing = [name for name in ALL if name not in shaders]
        if len(shaders) < 10 or missing:
            problems.append(f"{SOURCE.name}: extracted {len(shaders)} shaders, expected 10; missing "
                            f"{missing}. The extractor has drifted and nothing below is meaningful.")

    for name, source in sorted(shaders.items()):
        lines = source.split("\n")
        if name in ES3:
            if lines[0] != "#version 300 es":
                problems.append(f"{name}: the first line is not the version directive: {lines[0]!r}")
            if source.count("#version") != 1:
                problems.append(f"{name}: {source.count('#version')} version directives in one shader")
            for legacy in ("attribute ", "varying ", "gl_FragColor", "texture2D("):
                if legacy in strip_comments(source):
                    problems.append(f"{name}: a 300 es shader uses 1.00 syntax ({legacy!r})")
        elif source.lstrip().startswith("#version"):
            problems.append(f"{name}: a demo shader declares a version; it must stay on the 1.00 default")

        for identifier in sorted(set(identifiers(source))):
            if identifier in RESERVED:
                problems.append(f"{name}: '{identifier}' is reserved in GLSL ES 3.00; a driver that "
                                "follows the specification rejects the whole shader for it")

    for name in FRAGMENTS:
        if name not in shaders:
            continue
        # ESSL has no default float precision in the fragment language: it must be stated, near the top.
        header = "\n".join(shaders[name].split("\n")[:3])
        if "precision " not in header:
            problems.append(f"{name}: no float precision is declared at the top of the fragment shader")

    for vertex_name, fragment_name, (out_keyword, in_keyword), label in PAIRS:
        if vertex_name not in shaders or fragment_name not in shaders:
            continue
        written = declarations(shaders[vertex_name], out_keyword)
        read = declarations(shaders[fragment_name], in_keyword)
        if not read:
            problems.append(f"{label}: the fragment stage reads no varyings, so nothing is checked")
        for name, (kind, precision) in sorted(read.items()):
            if name not in written:
                problems.append(f"{label}: the fragment stage reads '{name}' that the vertex stage "
                                f"never writes: {sorted(written)}")
                continue
            if written[name][0] != kind:
                problems.append(f"{label}: '{name}' is {written[name][0]} in the vertex stage and "
                                f"{kind} in the fragment stage")
            if precision is None or written[name][1] is None:
                problems.append(f"{label}: '{name}' crosses a stage boundary without an explicit "
                                f"precision qualifier (vertex={written[name][1]}, fragment={precision})")
            elif precision != written[name][1]:
                problems.append(f"{label}: '{name}' is {written[name][1]} in the vertex stage and "
                                f"{precision} in the fragment stage, which is the mismatch a device "
                                "refused to link")
        # One uniform declared in two stages is one uniform: same precision or no program.
        vertex_uniforms = declarations(shaders[vertex_name], "uniform")
        fragment_uniforms = declarations(shaders[fragment_name], "uniform")
        for name in sorted(set(vertex_uniforms) & set(fragment_uniforms)):
            first, second = vertex_uniforms[name][1], fragment_uniforms[name][1]
            if first is None or second is None:
                problems.append(f"{label}: uniform '{name}' is declared in both stages without an "
                                f"explicit precision (vertex={first}, fragment={second})")
            elif first != second:
                problems.append(f"{label}: uniform '{name}' is {first} in the vertex stage and {second} "
                                "in the fragment stage: a driver will refuse to link the program")
    return problems


def sample(vertex_lines: list[str], fragment_lines: list[str]) -> dict[str, str]:
    def text(lines: list[str]) -> str:
        return "\n".join(lines) + "\n"
    return {"VERTEX": text(vertex_lines), "FRAGMENT": text(fragment_lines)}


RESERVED_SAMPLE = sample(
    ["#version 300 es", "in highp vec3 a_position;", "out highp vec3 v_world;", "void main() {",
     "  vec3 half = a_position;", "  v_world = half;", "  gl_Position = vec4(half, 1.0);", "}"],
    ["#version 300 es", "precision highp float;", "in highp vec3 v_world;", "out vec4 fragColour;",
     "void main() { fragColour = vec4(v_world, 1.0); }"])

PRECISION_SAMPLE = sample(
    ["#version 300 es", "uniform vec3 u_cameraLocal;", "out highp vec3 v_world;", "void main() {",
     "  v_world = u_cameraLocal;", "  gl_Position = vec4(0.0, 0.0, 0.0, 1.0);", "}"],
    ["#version 300 es", "precision highp float;", "uniform vec3 u_cameraLocal;",
     "in highp vec3 v_world;", "out vec4 fragColour;",
     "void main() { fragColour = vec4(v_world * u_cameraLocal.x, 1.0); }"])

GOOD_SAMPLE = sample(
    ["#version 300 es", "uniform mediump vec3 u_cameraLocal;", "out mediump vec3 v_world;",
     "void main() {", "  v_world = u_cameraLocal;",
     "  gl_Position = vec4(0.0, 0.0, 0.0, 1.0);", "}"],
    ["#version 300 es", "precision highp float;", "uniform mediump vec3 u_cameraLocal;",
     "in mediump vec3 v_world;", "out vec4 fragColour;",
     "void main() { fragColour = vec4(v_world * u_cameraLocal.x, 1.0); }"])


def selftest() -> int:
    """Prove the checker fails on the bugs that reached a phone, and passes on the repaired shaders."""
    failures = 0
    cases = [
        ("a reserved word as a variable name", RESERVED_SAMPLE, "reserved"),
        ("a shared uniform with no qualifier", PRECISION_SAMPLE, "without an explicit precision"),
        ("both repaired", GOOD_SAMPLE, None),
    ]
    for label, shaders, expected in cases:
        problems = check(shaders, complete=False)
        if expected is None:
            ok = not problems
        else:
            ok = any(expected in problem for problem in problems) and len(problems) == 1
        if ok:
            print(f"ok    selftest '{label}': {len(problems)} problem(s)")
        else:
            print(f"FAIL  selftest '{label}': {problems}")
            failures += 1
    return failures


def main() -> int:
    print("== shader gate ==")
    failures = selftest()
    shaders = extract(SOURCE.read_text(encoding="utf-8"))
    print(f"== {SOURCE.relative_to(REPO)} ({len(shaders)} shaders) ==")
    problems = check(shaders)
    for problem in problems:
        print("FAIL ", problem)
    print(f"{len(problems)} problem(s)")
    if failures or problems:
        print(f"SHADER GATE FAILED ({failures + len(problems)} problem(s))")
        return 1
    print("SHADER GATE PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
