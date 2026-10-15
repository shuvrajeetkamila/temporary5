#!/usr/bin/env python3
"""Rejects Java files that hand a value to a format specifier that cannot print it.

    python3 tools/check_format_specifiers.py [files or directories]

Why this exists
---------------

The sixth install of the game drew the world and reported, in the app's own words, "Failed the
controls": `IllegalFormatConversionException: f != java.lang.Integer`, six frames of
`java.util.Formatter` underneath it. The cause was one specifier in the HUD's status line --
`"%.0f fps"` against `Gdx.graphics.getFramesPerSecond()`, which libGDX declares as `int`. Java's
formatter refuses to print an Integer fractionally, so it threw; the status line is drawn inside the
"controls" half of the frame, so the whole half failed, every frame, and after two seconds of it the
game stopped drawing the screen and left the diagnostics panel up.

Nothing in this project could see that. It is not a syntax error, not a missing import, not a
duplicate local, and not a narrowing conversion: every one of those gates passes on `"%.0f"` with an
`int`, because as far as Java's type system is concerned the argument is fine -- `String.format` takes
`Object...`. The mismatch lives in a string that is only interpreted at runtime, on a phone.

What it checks, and what it refuses to check
--------------------------------------------

Only a format string that is a *literal in the call* can be read at all -- a format built by
concatenation is skipped, silently and by design. Given a literal, three things are provable:

  1. **Too few values.** A specifier that consumes a value needs one, and a count over the number of
     values given is a `MissingFormatArgumentException` at runtime.
  2. **A floating-point specifier (`f`, `e`, `g`, `a`) given an integer, a character, a boolean or a
     string.** The first of those is the sixth install's exception; none of the four can be printed
     fractionally.
  3. **An integral specifier (`d`, `o`, `x`, `X`) given a float, a character, a boolean or a string.**

A value's kind is used only when it can be *proved*: a literal, a local, parameter or field with a
declared type, a cast, arithmetic over known kinds, a call to a method declared **unqualified** in this
repository with a single return type, or a call to one of a short list of library accessors this
project has checked by hand (`getFramesPerSecond` is an `int`; `getDeltaTime` is a `float`). Anything
else -- an array element, a call into unknown code, a method called on some object -- is unknown, and
an unknown is never reported.

That conservatism is the point. A gate that cries wolf is a gate that gets switched off, and this one
is worth having only while it can be trusted. Its self-tests run first, on samples that include the
exact line that cost the sixth install.

Exit code 0 when nothing is wrong, 1 when something is, naming the file, the line and the specifier.
"""

from __future__ import annotations

import re
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

METHODS = {"method_declaration", "constructor_declaration", "static_initializer", "lambda_expression"}
DECLARATIONS = {"local_variable_declaration", "formal_parameter", "spread_parameter",
                "catch_formal_parameter", "resource"}

INTEGER_TYPES = {"int", "long", "short", "byte"}
# Java's own grammar for a specifier: argument index, flags, width, precision, date prefix, conversion.
SPECIFIER = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+)?(?:\.(\d+))?(?:[tT])?([a-zA-Z%])")
FLOATING_CONVERSIONS = set("eEfgGaA")
INTEGRAL_CONVERSIONS = set("doxX")
# What each family of numeric specifiers can and cannot print. A `%f` wants a decimal and refuses a
# whole number -- that refusal is the sixth install -- and a `%d` is the mirror image of it. A char, a
# boolean and a string are refused by both.
BAD_FOR_FLOATING = ("an integer", "a character", "a boolean", "a string")
BAD_FOR_INTEGRAL = ("a float", "a character", "a boolean", "a string")

# Return types this project relies on from outside itself. Kept short on purpose: every entry is a
# claim about somebody else's code, and a wrong claim here is an innocent line reported as a bug.
LIBRARY_RETURNS = {
    "getFramesPerSecond": "int",
    "getWidth": "int",
    "getHeight": "int",
    "getDeltaTime": "float",
    "getRawDeltaTime": "float",
}

DOUBLE_MATH = {"sqrt", "hypot", "cbrt", "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
               "toRadians", "toDegrees", "exp", "log", "log10", "pow", "floor", "ceil", "rint",
               "expm1", "log1p", "sinh", "cosh", "tanh", "IEEEremainder", "random"}
ARGUMENT_MATH = {"max", "min", "abs", "copySign", "signum"}

KIND_INT = "an integer"
KIND_FLOAT = "a float"
KIND_STRING = "a string"
KIND_CHAR = "a character"
KIND_BOOLEAN = "a boolean"
UNKNOWN = ""


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


def type_text(node, source: bytes) -> str:
    field = node.child_by_field_name("type")
    return re.sub(r"\s+", "", text_of(field, source)) if field is not None else ""


def kind_of_type(declared: str) -> str:
    """The kind of value a declared type holds, as this gate reasons about it."""
    if declared in INTEGER_TYPES:
        return KIND_INT
    if declared in ("float", "double"):
        return KIND_FLOAT
    if declared == "boolean":
        return KIND_BOOLEAN
    if declared == "char":
        return KIND_CHAR
    if declared == "String" or declared == "CharSequence":
        return KIND_STRING
    return UNKNOWN


def enclosing_method(node):
    current = node.parent
    while current is not None:
        if current.type in METHODS:
            return current
        current = current.parent
    return None


def repository_returns(files: list[Path], parse) -> dict[str, str]:
    """Method name -> return type, for methods declared anywhere in this tree.

    A name declared with two different return types is dropped rather than guessed at, and only
    unqualified calls are matched against this table: a method called on an object could be any
    class's, including a library's, and proving a kind from the wrong declaration is how a check
    starts lying.
    """
    types: dict[str, set[str]] = {}
    for path in files:
        source = path.read_bytes()
        for node in walk(parse(source).root_node):
            if node.type not in ("method_declaration", "constructor_declaration"):
                continue
            declared = type_text(node, source)
            name = node.child_by_field_name("name")
            if name is None or not declared or declared == "void":
                continue
            types.setdefault(text_of(name, source), set()).add(declared)
    return {name: next(iter(found)) for name, found in types.items() if len(found) == 1}


def method_values(method, source: bytes) -> dict[str, str]:
    """Every name declared inside one method, with the kind of value it holds."""
    values: dict[str, str] = {}
    for node in walk(method):
        if node.type not in DECLARATIONS:
            continue
        kind = kind_of_type(type_text(node, source))
        if not kind:
            continue
        for child in node.children:
            if child.type in ("variable_declarator", "formal_parameter", "spread_parameter",
                              "catch_formal_parameter"):
                name = child.child_by_field_name("name")
                if name is not None:
                    values[text_of(name, source)] = kind
        name = node.child_by_field_name("name")
        if name is not None:
            values[text_of(name, source)] = kind
    return values


def field_values(root, source: bytes) -> dict[str, str]:
    values: dict[str, str] = {}
    for node in walk(root):
        if node.type != "field_declaration":
            continue
        kind = kind_of_type(type_text(node, source))
        if not kind:
            continue
        for child in node.children:
            if child.type == "variable_declarator":
                name = child.child_by_field_name("name")
                if name is not None:
                    values[text_of(name, source)] = kind
    return values


def combine(first: str, second: str) -> str:
    """The kind two values produce under arithmetic."""
    if first == KIND_FLOAT or second == KIND_FLOAT:
        return KIND_FLOAT
    if first == KIND_INT and second == KIND_INT:
        return KIND_INT
    return UNKNOWN


def value_kind(node, source: bytes, values: dict[str, str], returns: dict[str, str]) -> str:
    """What kind of value this expression is, as far as it can be proved from its shape."""
    kind = node.type
    if kind == "parenthesized_expression":
        inner = next((child for child in node.children if child.type not in ("(", ")")), None)
        return value_kind(inner, source, values, returns) if inner is not None else UNKNOWN
    if kind == "cast_expression":
        return kind_of_type(type_text(node, source))
    if kind in ("decimal_floating_point_literal", "hex_floating_point_literal"):
        return KIND_FLOAT
    if kind in ("decimal_integer_literal", "hex_integer_literal", "octal_integer_literal",
                "binary_integer_literal"):
        return KIND_INT
    if kind == "string_literal":
        return KIND_STRING
    if kind == "character_literal":
        return KIND_CHAR
    if kind in ("true", "false"):
        return KIND_BOOLEAN
    if kind == "identifier":
        return values.get(text_of(node, source), UNKNOWN)
    if kind == "field_access":
        field = node.child_by_field_name("field")
        return values.get(text_of(field, source), UNKNOWN) if field is not None else UNKNOWN
    if kind in ("binary_expression", "ternary_expression"):
        operator = node.child_by_field_name("operator")
        if operator is not None and text_of(operator, source) in (
                "<", ">", "<=", ">=", "==", "!=", "&&", "||"):
            return KIND_BOOLEAN
        if kind == "ternary_expression":
            consequence = node.child_by_field_name("consequence")
            alternative = node.child_by_field_name("alternative")
            return combine(value_kind(consequence, source, values, returns)
                           if consequence is not None else UNKNOWN,
                           value_kind(alternative, source, values, returns)
                           if alternative is not None else UNKNOWN)
        left = node.child_by_field_name("left")
        right = node.child_by_field_name("right")
        return combine(value_kind(left, source, values, returns) if left is not None else UNKNOWN,
                       value_kind(right, source, values, returns) if right is not None else UNKNOWN)
    if kind == "unary_expression":
        operand = node.child_by_field_name("operand")
        return value_kind(operand, source, values, returns) if operand is not None else UNKNOWN
    if kind == "method_invocation":
        name_node = node.child_by_field_name("name")
        method = text_of(name_node, source) if name_node is not None else ""
        object_node = node.child_by_field_name("object")
        owner = text_of(object_node, source) if object_node is not None else ""
        if owner == "Math":
            if method in DOUBLE_MATH:
                return KIND_FLOAT
            if method == "round":
                return KIND_INT
            if method in ARGUMENT_MATH:
                arguments = node.child_by_field_name("arguments")
                kinds = [value_kind(child, source, values, returns)
                         for child in (arguments.children if arguments is not None else [])]
                result = KIND_INT
                for one in kinds:
                    result = combine(result, one)
                return result
        if method in ("toString", "valueOf", "format"):
            return KIND_STRING
        if object_node is None:
            declared = returns.get(method)
            if declared is not None:
                return kind_of_type(declared)
        if method in LIBRARY_RETURNS:
            return kind_of_type(LIBRARY_RETURNS[method])
        return UNKNOWN
    return UNKNOWN


def format_call(node, source: bytes):
    """The format pattern and the values, when this is a String.format on a literal in the call."""
    name_node = node.child_by_field_name("name")
    if name_node is None or text_of(name_node, source) != "format":
        return None
    object_node = node.child_by_field_name("object")
    owner = text_of(object_node, source) if object_node is not None else ""
    if owner not in ("String", "java.lang.String"):
        return None
    arguments = node.child_by_field_name("arguments")
    if arguments is None:
        return None
    children = [child for child in arguments.children if child.type not in ("(", ")", ",")]
    literal = None
    literal_at = -1
    for index, child in enumerate(children):
        if index > 1:
            break
        if child.type == "string_literal":
            literal = child
            literal_at = index
            break
    if literal is None or literal_at not in (0, 1):
        return None
    raw = text_of(literal, source)
    if len(raw) < 2 or not raw.startswith('"') or not raw.endswith('"'):
        return None
    pattern = raw[1:-1].replace('\\\\', '\\').replace('\\"', '"')
    return pattern, children[literal_at + 1:]


def marked_intentional(source: bytes, node) -> bool:
    """Whether the call says, on its own line or the one above, that the mismatch is the point.

    A test that asserts *that Java refuses* `%.0f` with an int has to contain the refusal -- one line
    of this repository's own code is deliberately wrong, on purpose, to prove the platform rejects it.
    The marker is the same idea as a suppression annotation: rare, visible in review, and never a way
    to hide a mistake in shipped code, because nothing in `main` uses it.
    """
    line_start = source.rfind(b"\n", 0, node.start_byte) + 1
    previous_start = source.rfind(b"\n", 0, max(0, line_start - 1)) + 1
    first_line_end = source.find(b"\n", node.start_byte)
    if first_line_end < 0:
        first_line_end = len(source)
    return b"format-gate: intentional" in source[previous_start:first_line_end]


def check_call(node, source: bytes, values: dict[str, str], returns: dict[str, str],
               path: Path) -> list[str]:
    call = format_call(node, source)
    if call is None:
        return []
    if marked_intentional(source, node):
        return []
    pattern, arguments = call
    line = source[:node.start_byte].count(b"\n") + 1
    # Which value each specifier reads: an explicit index (`%2$s`) wins, `<` reuses the previous one,
    # and everything else takes the next in order. `%%` and `%n` take no value at all.
    order = []
    cursor = 0
    for match in SPECIFIER.finditer(pattern):
        conversion = match.group(5)
        if conversion == "%" or conversion == "n":
            continue
        index_text = match.group(1)
        flags = match.group(2)
        if index_text:
            cursor = int(index_text) - 1
        elif flags and "<" in flags:
            cursor = max(0, cursor - 1)
        order.append((match.group(0), conversion, cursor))
        cursor += 1
    needed = max((index for _, _, index in order), default=-1) + 1
    if needed > len(arguments):
        return [f"{path}:{line}: the format needs {needed} value(s) and is given {len(arguments)} "
                f"-- MissingFormatArgumentException at runtime"]
    problems = []
    for specifier, conversion, index in order:
        if index >= len(arguments):
            continue
        kind = value_kind(arguments[index], source, values, returns)
        if not kind:
            continue                                    # not provable, so not reported
        if conversion in FLOATING_CONVERSIONS:
            if kind not in BAD_FOR_FLOATING:
                continue
        elif conversion in INTEGRAL_CONVERSIONS:
            if kind not in BAD_FOR_INTEGRAL:
                continue
        else:
            continue                                     # `%s` prints anything
        snippet = re.sub(r"\s+", " ", text_of(arguments[index], source)).strip()
        if len(snippet) > 46:
            snippet = snippet[:43] + "..."
        if conversion in FLOATING_CONVERSIONS:
            problems.append(f"{path}:{line}: {specifier} cannot print {kind} ({snippet}): Java throws "
                            f"IllegalFormatConversionException, and a status line that throws takes its "
                            f"half of the frame down with it")
        elif conversion in INTEGRAL_CONVERSIONS:
            problems.append(f"{path}:{line}: {specifier} cannot print {kind} ({snippet}): Java throws "
                            f"IllegalFormatConversionException")
    return problems


def check_file(path: Path, parse, returns: dict[str, str]) -> list[str]:
    source = path.read_bytes()
    root = parse(source).root_node
    fields = field_values(root, source)
    per_method: dict[int, dict[str, str]] = {}
    problems: list[str] = []
    for node in walk(root):
        if node.type != "method_invocation":
            continue
        method = enclosing_method(node)
        values = dict(fields)
        if method is not None:
            # The binding's own node id, not `id()`: two wrappers for the same node are different
            # objects, so `id()` is not stable across a walk.
            if method.id not in per_method:
                per_method[method.id] = method_values(method, source)
            values.update(per_method[method.id])
        problems.extend(check_call(node, source, values, returns, path))
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
    """The check has to fail on the line that cost the sixth install, and pass on legal code."""
    import tempfile

    cases = [
        ("the mistake: %.0f against the frame count libGDX declares as int", """
class A {
    void run() {
        String line = String.format("%s | %.0f fps", "body", com.badlogic.gdx.Gdx.graphics.getFramesPerSecond());
    }
}
""", 1),
        ("the repaired line: %d for a whole number of frames", """
class A {
    void run() {
        String line = String.format("%s | %d fps", "body", com.badlogic.gdx.Gdx.graphics.getFramesPerSecond());
    }
}
""", 0),
        ("%.0f against an int local", """
class A {
    void run() {
        int fps = 60;
        String line = String.format("%.0f fps", fps);
    }
}
""", 1),
        ("%.0f against a double local, which is what the specifier is for", """
class A {
    void run() {
        double speed = 3.5;
        String line = String.format("%.0f m/s", speed);
    }
}
""", 0),
        ("%d against a double literal", """
class A {
    void run() {
        String line = String.format("%d frames", 1.5);
    }
}
""", 1),
        ("a string handed to a numeric specifier", """
class A {
    void run(String name) {
        String line = String.format("%d bytes", name);
    }
}
""", 1),
        ("too few values for the specifiers", """
class A {
    void run() {
        String line = String.format("%s %s %s", "one", "two");
    }
}
""", 1),
        ("%% and %n take no value", """
class A {
    void run() {
        String line = String.format("100%% of %s%n", "it");
    }
}
""", 0),
        ("an explicit argument index, counted properly", """
class A {
    void run() {
        String line = String.format("%2$s %1$s", "second", "first");
    }
}
""", 0),
        ("an unknown value is never reported", """
class A {
    void run(float[] v, int at) {
        String line = String.format("%.0f", v[at]);
        String other = String.format("%.0f", someCall(at));
    }
    double someCall(int at) { return at; }
}
""", 0),
        ("a format built by concatenation cannot be read, and is skipped", """
class A {
    void run(int fps) {
        String line = String.format("%s" + " | %.0f", "body", fps);
    }
}
""", 0),
        ("a method declared in this tree is a provable kind", """
class A {
    static int frameCount() { return 60; }
    void run() {
        String line = String.format("%.0f fps", frameCount());
    }
}
""", 1),
        ("a mismatch an author has marked as deliberate is left alone", """
class A {
    void run() {
        // This line is wrong on purpose, to prove the platform refuses it.
        // format-gate: intentional
        String line = String.format("%.0f fps", 60);
    }
}
""", 0),
        ("a method called on an object proves nothing, so it is not reported", """
class A {
    void run(Window window) {
        String line = String.format("%.0f px", window.width());
    }
    static class Window {
        int width() { return 3; }
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
            parse = parse_source()
            found = len(check_file(path, parse, repository_returns([path], parse)))
            ok = (found > 0) == (expected > 0)
            print(f"{'ok   ' if ok else 'FAIL '} selftest '{label}': {found} problem(s)")
            failures += 0 if ok else 1
    return failures


def run(arguments: list[str]) -> int:
    files = targets(arguments)
    parse = parse_source()
    returns = repository_returns(files, parse)
    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path, parse, returns))
    print("== format gate ==")
    print(f"scanned {len(files)} Java files, {len(returns)} known method return types, "
          f"{len(problems)} problem(s)")
    for problem in problems:
        print(problem)
    print("FORMAT GATE " + ("PASSED" if not problems else "FAILED"))
    return 1 if problems else 0


def main(argv: list[str]) -> int:
    if not argv:
        failures = selftest()
        if failures:
            print(f"FORMAT GATE SELFTEST FAILED ({failures})")
            return 1
        return run(DEFAULT_ROOTS)
    return run(argv)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
