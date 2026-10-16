#!/usr/bin/env python3
"""Rejects 2D drawing that does not set the GL state it needs.

    python3 tools/check_ui_state.py [files or directories]

Why this exists
---------------

On a phone, the seventh install of the game drew the country perfectly and no controls at all: no
buttons, no minimap, no status line, and no on-screen diagnostics panel either. Nothing had thrown, and
the code that draws the HUD was correct.

What was wrong was the GL state. The world renderer enables `GL_CULL_FACE` and `GL_DEPTH_TEST` at the
start of each frame and never turns either off. libGDX's `SpriteBatch.begin()` — read from its own
source, 1.12.1 — sets `glDepthMask(false)` and nothing else. And SpriteBatch emits its sprites as
(x,y), (x,y+h), (x+w,y+h), (x+w,y), indexed 0-1-2, 2-3-0: both triangles clockwise in a y-up
coordinate system, against the counter-clockwise front face the world renderer leaves behind. Clockwise
triangles with a counter-clockwise front are back faces, so with culling on the GPU discarded every
quad the HUD drew.

That is invisible to every test in this repository, because none of them owns a GPU. It is catchable at
the source, though, and this is the catch: **every call to `SpriteBatch.begin()` must be preceded, in
the same method, by a call to `GlState2D.beginUi()`** — the one place that disables depth testing and
face culling for 2D.

What it checks
--------------

For each Java file: every method that calls `begin()` on something that looks like a batch (`batch`
or any field whose name ends in `batch`, or a SpriteBatch local) must call `GlState2D.beginUi()` earlier
in that method. A `beginUi()` call in the same method is enough; it does not need to be the line before,
because a method may do other preparation first — what matters is that it happens before the batch
opens. Anything the checker cannot follow — a batch passed in as a parameter, a call inside a lambda
whose enclosing method is not visible — is skipped rather than guessed at.

There is a second rule here, from the ninth and tenth installs, and it is the same kind of bug: state
that a 2D drawing call needs and that no compiler checks. **A `TextureRegion` that is drawn must have
been given a texture.** libGDX's `SpriteBatch.draw(TextureRegion, ...)` reads `region.getTexture()` and
then calls `texture.getWidth()` on it; a region built with `new TextureRegion()` and given only a
rectangle -- `setRegion(u, v, u2, v2)` -- has no texture, so the draw throws
`NullPointerException: Attempt to invoke virtual method 'int ...Texture.getWidth()' on a null object
reference` on the GL thread, every frame, in the code that draws the full map. That was the tenth
install's "opening the minimap crashes": the map raster was fine, and the region the map was cropped
through never had a texture.

Exit code 0 when nothing is wrong, 1 when something is, naming the file, the line and the method.
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import tree_sitter
    import tree_sitter_java
except ImportError:  # pragma: no cover - the CI job installs these
    print("tree-sitter and tree-sitter-java are required: pip install tree-sitter tree-sitter-java",
          file=sys.stderr)
    raise SystemExit(2)

DEFAULT_ROOTS = ["core/src", "android/src"]

METHODS = {"method_declaration", "constructor_declaration", "static_initializer"}
STATE_CALL = "beginUi"
STATE_CLASS = "GlState2D"


def parser():
    language = tree_sitter.Language(tree_sitter_java.language())
    return tree_sitter.Parser(language)


def parse_source():
    java_parser = parser()

    def parse(source: bytes):
        return java_parser.parse(source)

    return parse


def walk(root):
    stack = [root]
    while stack:
        node = stack.pop()
        yield node
        stack.extend(node.children)


def text_of(node, source: bytes) -> str:
    return source[node.start_byte:node.end_byte].decode("utf-8", "replace")


def line_of(source: bytes, node) -> int:
    return source[:node.start_byte].count(b"\n") + 1


def enclosing_method(node):
    current = node.parent
    while current is not None:
        if current.type in METHODS:
            return current
        current = current.parent
    return None


def batch_fields(root, source: bytes) -> set[str]:
    """Names of fields that hold a SpriteBatch: `batch`, or anything called `*Batch`."""
    names = set()
    for node in walk(root):
        if node.type != "field_declaration":
            continue
        declared = node.child_by_field_name("type")
        if declared is None:
            continue
        type_text = text_of(declared, source)
        if "SpriteBatch" not in type_text and not type_text.endswith("Batch"):
            continue
        for child in node.children:
            if child.type != "variable_declarator":
                continue
            name = child.child_by_field_name("name")
            if name is not None:
                names.add(text_of(name, source))
    return names


def method_batch_names(method, source: bytes, fields: set[str]) -> set[str]:
    """Every name inside one method that holds a batch: its fields, its parameters, its locals.

    The local matters as much as the field -- a batch created inside a draw method is the same hazard --
    and a declaration is not an ancestor of the call that uses it, so the names are collected over the
    whole method rather than looked for on the way up from the call.
    """
    names = set(fields)
    for node in walk(method):
        if node.type not in ("local_variable_declaration", "formal_parameter"):
            continue
        declared = node.child_by_field_name("type")
        if declared is None or "Batch" not in text_of(declared, source):
            continue
        for child in node.children:
            if child.type in ("variable_declarator", "formal_parameter"):
                name = child.child_by_field_name("name")
                if name is not None:
                    names.add(text_of(name, source))
        name = node.child_by_field_name("name")
        if name is not None:
            names.add(text_of(name, source))
    return names


def is_batch_begin(node, source: bytes, names: set[str]) -> bool:
    """Whether this call is `X.begin()` where X holds a batch."""
    if node.type != "method_invocation":
        return False
    name = node.child_by_field_name("name")
    if name is None or text_of(name, source) != "begin":
        return False
    receiver = node.child_by_field_name("object")
    if receiver is None:
        return False
    return text_of(receiver, source) in names


def calls_begin_ui(method, source: bytes) -> bool:
    for node in walk(method):
        if node.type == "method_invocation":
            name = node.child_by_field_name("name")
            if name is not None and text_of(name, source) == STATE_CALL:
                return True
            if STATE_CALL in text_of(node, source):
                return True
    return False


def check_file(path: Path, parse) -> list[str]:
    source = path.read_bytes()
    root = parse(source).root_node
    fields = batch_fields(root, source)
    problems: list[str] = []
    for node in walk(root):
        if node.type not in METHODS:
            continue
        names = method_batch_names(node, source, fields)
        opens = [child for child in walk(node)
                 if is_batch_begin(child, source, names)
                 and enclosing_method(child) is not None and enclosing_method(child).id == node.id]
        if not opens:
            continue
        if calls_begin_ui(node, source):
            continue
        name_node = node.child_by_field_name("name")
        name = text_of(name_node, source) if name_node is not None else "(initialiser)"
        for open_call in opens:
            problems.append(f"{path}:{line_of(source, open_call)}: {name}() opens a SpriteBatch without "
                            f"calling {STATE_CLASS}.{STATE_CALL}() first -- the world renderer leaves "
                            f"culling and depth testing on, and a culled HUD draws nothing on a phone")
    problems.extend(regions_drawn_without_texture(path, parse))
    return problems


REGION_TYPE = "TextureRegion"


def region_fields(root, source: bytes) -> dict[str, int]:
    """Every TextureRegion field in the file, by name, with the line it is declared on."""
    found: dict[str, int] = {}
    for node in walk(root):
        if node.type != "field_declaration":
            continue
        type_node = node.child_by_field_name("type")
        if type_node is None or REGION_TYPE not in text_of(type_node, source):
            continue
        for child in node.children:
            if child.type != "variable_declarator":
                continue
            name_node = child.child_by_field_name("name")
            if name_node is not None:
                found[text_of(name_node, source)] = line_of(source, node)
    return found


def region_has_texture(name: str, root, source: bytes) -> bool:
    """True when something in the file gives this region a texture.

    Two legal forms, and both are read from the call rather than guessed at: the one-argument
    `setRegion(texture)`, which is libGDX's own way of setting the texture *and* the rectangle, and
    `new TextureRegion(texture)`, which sets it at construction. `setRegion(u, v, u2, v2)` -- four
    arguments -- sets only the rectangle and is deliberately not counted.
    """
    for node in walk(root):
        if node.type == "object_creation_expression":
            text = text_of(node, source).replace(" ", "")
            if text.startswith(f"new{REGION_TYPE}(") and not text.startswith(f"new{REGION_TYPE}()"):
                continue                                  # a new region, not this field
        if node.type != "method_invocation":
            continue
        receiver = node.child_by_field_name("object")
        if receiver is None or text_of(receiver, source) != name:
            continue
        arguments = [child for child in node.children if child.type == "argument_list"]
        if not arguments:
            continue
        count = sum(1 for _ in walk(arguments[0]) if _.type in ("identifier", "field_access",
                                                               "method_invocation"))
        if count != 1:
            # One argument means a Texture; four means a rectangle with no texture behind it.
            continue
        return True
    return False


def regions_drawn_without_texture(path: Path, parse) -> list[str]:
    source = path.read_bytes()
    root = parse(source).root_node
    fields = region_fields(root, source)
    if not fields:
        return []
    problems: list[str] = []
    for name, line in fields.items():
        drawn_at = None
        for node in walk(root):
            if node.type != "method_invocation":
                continue
            call_name = node.child_by_field_name("name")
            if call_name is None or text_of(call_name, source) not in ("draw", "drawRegion"):
                continue
            text = text_of(node, source)
            if f"({name}," not in text and f"({name})" not in text:
                continue
            drawn_at = line_of(source, node)
            break
        if drawn_at is None:
            continue                                       # never drawn: nothing to go wrong
        if not region_has_texture(name, root, source):
            problems.append(
                f"{path}:{drawn_at}: '{name}' is drawn with no texture behind it -- it was given a "
                f"rectangle and never a Texture, so SpriteBatch.draw calls getWidth() on null; give "
                f"it the texture where the texture is created (see HudRenderer.init)")
    return problems


def targets(arguments: list[str]) -> list[Path]:
    files: list[Path] = []
    for argument in arguments:
        path = Path(argument)
        if path.is_dir():
            files.extend(sorted(path.rglob("*.java")))
        elif path.suffix == ".java":
            files.append(path)
    return files


def selftest() -> int:
    """The check has to fail on the mistake the seventh install found, and pass on legal code."""
    import tempfile

    cases = [
        ("a batch opened with no state call, which is the seventh install", """
class A {
    private final SpriteBatch batch = new SpriteBatch();
    void draw() {
        batch.setProjectionMatrix(m);
        batch.begin();
    }
}
""", 1),
        ("the same method with the state call", """
class A {
    private final SpriteBatch batch = new SpriteBatch();
    void draw() {
        GlState2D.beginUi();
        batch.setProjectionMatrix(m);
        batch.begin();
    }
}
""", 0),
        ("a fully qualified state call", """
class A {
    private final SpriteBatch batch = new SpriteBatch();
    void draw() {
        ai.arena.qatar.render.GlState2D.beginUi();
        batch.begin();
    }
}
""", 0),
        ("a differently named batch field", """
class A {
    private final SpriteBatch panelBatch = new SpriteBatch();
    void draw() {
        panelBatch.begin();
    }
}
""", 1),
        ("a batch local, not a field", """
class A {
    void draw() {
        SpriteBatch local = new SpriteBatch();
        local.begin();
    }
}
""", 1),
        ("two methods: the state set in one does not cover the other", """
class A {
    private final SpriteBatch batch = new SpriteBatch();
    void prepared() {
        GlState2D.beginUi();
        batch.begin();
    }
    void other() {
        batch.begin();
    }
}
""", 1),
        ("begin() on something that is not a batch is left alone", """
class A {
    void run(Thing thing) {
        thing.begin();
    }
}
""", 0),
        ("a file with no 2D drawing at all", """
class A {
    void run() {
        GlState2D.beginUi();
    }
}
""", 0),
        ("a TextureRegion drawn with no texture, which is the tenth install", """
class A {
    private final SpriteBatch batch = new SpriteBatch();
    private final TextureRegion crop = new TextureRegion();
    void draw() {
        GlState2D.beginUi();
        batch.begin();
        crop.setRegion(0.1f, 0.1f, 0.9f, 0.9f);
        batch.draw(crop, 0, 0, 100, 100);
    }
}
""", 1),
        ("the same region given its texture first, which is the fix", """
class A {
    private final SpriteBatch batch = new SpriteBatch();
    private final TextureRegion crop = new TextureRegion();
    void init(Texture map) {
        crop.setRegion(map);
    }
    void draw() {
        GlState2D.beginUi();
        batch.begin();
        crop.setRegion(0.1f, 0.1f, 0.9f, 0.9f);
        batch.draw(crop, 0, 0, 100, 100);
    }
}
""", 0),
        ("a region made straight from a texture is left alone", """
class A {
    private final SpriteBatch batch = new SpriteBatch();
    void draw(Texture map) {
        TextureRegion region = new TextureRegion(map);
        GlState2D.beginUi();
        batch.begin();
        batch.draw(region, 0, 0, 100, 100);
    }
}
""", 0),
        ("a region that is never drawn is not a problem yet", """
class A {
    private final TextureRegion unused = new TextureRegion();
    void run() {
        unused.setRegion(0.0f, 0.0f, 1.0f, 1.0f);
    }
}
""", 0),
    ]
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        workspace = Path(directory)
        for label, source, expected in cases:
            path = workspace / "Case.java"
            path.write_text(source)
            found = len(check_file(path, parse_source()))
            ok = (found > 0) == (expected > 0)
            print(f"{'ok   ' if ok else 'FAIL '} selftest '{label}': {found} problem(s)")
            failures += 0 if ok else 1
    return failures


def run(arguments: list[str]) -> int:
    files = targets(arguments)
    parse = parse_source()
    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path, parse))
    print("== ui state gate ==")
    print(f"scanned {len(files)} Java files, {len(problems)} problem(s)")
    for problem in problems:
        print(problem)
    print("UI STATE GATE " + ("PASSED" if not problems else "FAILED"))
    return 1 if problems else 0


def main(argv: list[str]) -> int:
    if not argv:
        failures = selftest()
        if failures:
            print(f"UI STATE GATE SELFTEST FAILED ({failures})")
            return 1
        return run(DEFAULT_ROOTS)
    return run(argv)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
