#!/usr/bin/env python3
"""Rejects Java files that assign a double-typed expression to a float.

    python3 tools/check_float_narrowing.py [files or directories]

Why this exists
---------------

Java does not convert a `double` to a `float` implicitly. `float worst = Math.max(worst, v - CHUNK_M)`
does not compile, because the chunk size is a `double` and `Math.max(float, double)` is a `double`.
That is a one-line mistake with a six-minute price tag: the compile gate is the only thing in this
project that can see it, and it is the last step before Gradle. On the round that clipped the road
ribbons, exactly that line cost a whole run -- two errors, both of them mine, both of them in a test
file that every other gate had passed.

This is the third thing `javac` used to be the only one to catch, beside `check_java_symbols.py` (a
name that was never imported) and `check_local_names.py` (a name declared twice). It is deliberately
quiet: it reports only what it can *prove* is a double, and it says nothing about an expression whose
type it does not know. A gate that cries wolf gets switched off, and this one is only worth having if
it is trusted -- so the first draft of it, which read expressions as text and reported 64 innocent
lines, was thrown away for this one, which reads the parse tree.

How a type is decided
---------------------

Only the shape of the expression matters, because the question is always "what is the type of this
whole expression":

  * a cast is its own target type -- `(float) anything` is a float, whatever is inside it;
  * `a < b` is a boolean and cannot be a double, so a `double` in a comparison is not a problem;
  * `cond ? x : y` is the type of `x` and `y`, and the condition does not matter;
  * arithmetic is a double when either side is, a float when neither is and either is;
  * a decimal literal is a float with an `f` suffix and a double without one;
  * `Math.sqrt` and its family return a double; `Math.max`, `Math.min` and `Math.abs` return whatever
    their arguments say; `Math.round` returns a long, which does widen to a float on its own;
  * a name is a double when the method declares it, when the file declares the field, or when any
    `static final double` in the tree has that name (`CHUNK_M` is one of those);
  * anything else -- an array element, a call into this project's own code, a field of another class
    -- is *unknown*, and an unknown type is never reported.

Known limit, stated rather than implied: it is not a compiler, so a value that is really a double but
arrives from this project's own code is not caught. What it does catch is the mistake that has
actually cost a run.
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
DECLARATIONS = {"local_variable_declaration", "formal_parameter", "spread_parameter"}

# `Math` methods whose only overloads return double, whatever they are given.
DOUBLE_MATH = {"sqrt", "hypot", "cbrt", "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
               "toRadians", "toDegrees", "exp", "log", "log10", "pow", "floor", "ceil", "rint",
               "expm1", "log1p", "sinh", "cosh", "tanh", "IEEEremainder"}
# `Math` methods whose type is whatever their arguments are -- but a double argument makes a double.
ARGUMENT_MATH = {"max", "min", "abs", "copySign", "signum"}

COMPARISONS = {"<", ">", "<=", ">=", "==", "!=", "&&", "||"}
# The type of an expression, as this gate names it. One spelling, used by every branch: a first draft
# had the constants worded for the report and the lookups worded as type names, so "a double" never
# equalled "double" and the two most common cases silently passed.
DOUBLE, FLOAT, INT, BOOL, UNKNOWN = "double", "float", "int", "boolean", "unknown"


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
    """The declared type of a declaration or a method, without generics or whitespace."""
    field = node.child_by_field_name("type")
    if field is None:
        return ""
    return re.sub(r"\s+", "", text_of(field, source))


def modifiers_text(node, source: bytes) -> str:
    """The `static final` of a declaration. The grammar holds modifiers as a plain child, not a field,
    so asking for the `modifiers` field of a field that plainly says `static final` gives nothing."""
    for child in node.children:
        if child.type == "modifiers":
            return re.sub(r"\s+", " ", text_of(child, source))
    return ""


def names_declared_as(node, wanted: str, source: bytes) -> set[str]:
    """Every name a declaration or parameter list introduces with the type asked for."""
    names = set()
    if type_text(node, source) != wanted:
        return names
    for child in node.children:
        if child.type in ("variable_declarator", "formal_parameter", "spread_parameter",
                          "catch_formal_parameter"):
            name = child.child_by_field_name("name")
            if name is not None:
                names.add(text_of(name, source))
    if not names:
        name = node.child_by_field_name("name")
        if name is not None:
            names.add(text_of(name, source))
    return names


def field_types(root, source: bytes) -> dict[str, str]:
    """Every field of one file, by name, as double / float / int."""
    known: dict[str, str] = {}
    for node in walk(root):
        if node.type != "field_declaration":
            continue
        declared = type_text(node, source)
        kind = "double" if declared == "double" else "float" if declared == "float" \
            else "int" if declared in ("int", "long", "short", "byte", "char") else UNKNOWN
        for child in node.children:
            if child.type == "variable_declarator":
                name = child.child_by_field_name("name")
                if name is not None:
                    known[text_of(name, source)] = kind
    return known


def double_constants(files: list[Path], parse) -> set[str]:
    """Every `static final double` in the tree: the names an expression can be proved a double by."""
    names: set[str] = set()
    for path in files:
        source = path.read_bytes()
        root = parse(source).root_node
        for node in walk(root):
            if node.type != "field_declaration" or type_text(node, source) != "double":
                continue
            if "static final" not in modifiers_text(node, source):
                continue
            for child in node.children:
                if child.type == "variable_declarator":
                    name = child.child_by_field_name("name")
                    if name is not None:
                        names.add(text_of(name, source))
    return names


def numeric(first: str, second: str) -> str:
    """The type arithmetic between two values has."""
    if first == DOUBLE or second == DOUBLE:
        return DOUBLE
    if first == FLOAT or second == FLOAT:
        return FLOAT
    if first == INT and second == INT:
        return INT
    return UNKNOWN


def expression_type(node, source: bytes, names: dict[str, str], constants: set[str]) -> str:
    """What this expression's type is, as far as it can be proved from its shape."""
    kind = node.type
    if kind == "parenthesized_expression":
        inner = next((child for child in node.children if child.type not in ("(", ")")), None)
        return expression_type(inner, source, names, constants) if inner is not None else UNKNOWN
    if kind == "cast_expression":
        target = type_text(node, source)
        if target == "double":
            return DOUBLE
        if target == "float":
            return FLOAT
        if target in ("int", "long", "short", "byte", "char"):
            return INT
        return UNKNOWN
    if kind in ("binary_expression", "ternary_expression"):
        operator = node.child_by_field_name("operator")
        operator_text = text_of(operator, source) if operator is not None else ""
        if operator_text in COMPARISONS:
            return BOOL
        if kind == "ternary_expression":
            # `cond ? x : y` is the type of x and y; a double in the condition is not the question.
            consequence = node.child_by_field_name("consequence")
            alternative = node.child_by_field_name("alternative")
            return numeric(expression_type(consequence, source, names, constants)
                           if consequence is not None else UNKNOWN,
                           expression_type(alternative, source, names, constants)
                           if alternative is not None else UNKNOWN)
        left = node.child_by_field_name("left")
        right = node.child_by_field_name("right")
        return numeric(expression_type(left, source, names, constants) if left is not None else UNKNOWN,
                       expression_type(right, source, names, constants) if right is not None else UNKNOWN)
    if kind == "unary_expression":
        operand = node.child_by_field_name("operand")
        return expression_type(operand, source, names, constants) if operand is not None else UNKNOWN
    if kind == "identifier":
        return names.get(text_of(node, source), UNKNOWN)
    if kind == "field_access":
        field = node.child_by_field_name("field")
        name = text_of(field, source) if field is not None else ""
        if name in constants:
            return DOUBLE
        return names.get(name, UNKNOWN)
    if kind == "decimal_floating_point_literal":
        text = text_of(node, source)
        return FLOAT if text.endswith(("f", "F")) else DOUBLE
    if kind in ("decimal_integer_literal", "hex_integer_literal", "octal_integer_literal",
                "binary_integer_literal"):
        return INT
    if kind == "method_invocation":
        name_node = node.child_by_field_name("name")
        method = text_of(name_node, source) if name_node is not None else ""
        object_node = node.child_by_field_name("object")
        owner = text_of(object_node, source) if object_node is not None else ""
        if owner == "Math":
            if method in DOUBLE_MATH:
                return DOUBLE
            if method in ARGUMENT_MATH:
                arguments = node.child_by_field_name("arguments")
                kinds = [expression_type(child, source, names, constants)
                         for child in (arguments.children if arguments is not None else [])]
                result = INT
                for kind_of_argument in kinds:
                    result = numeric(result, kind_of_argument)
                return result
            if method == "round":
                return INT                      # a long or an int, and a long does widen to a float
        return UNKNOWN
    return UNKNOWN


def method_names(method, source: bytes) -> dict[str, str]:
    """Every name declared inside one method, with its type: the locals, the parameters, the fields."""
    names: dict[str, str] = {}
    for node in walk(method):
        if node.type not in DECLARATIONS:
            continue
        declared = type_text(node, source)
        kind = "double" if declared == "double" else "float" if declared == "float" \
            else "int" if declared in ("int", "long", "short", "byte", "char") else UNKNOWN
        for name in names_declared_as(node, declared, source):
            names[name] = kind
    return names


def enclosing_method(node):
    current = node.parent
    while current is not None:
        if current.type in METHODS:
            return current
        current = current.parent
    return None


def check_file(path: Path, parse, constants: set[str]) -> list[str]:
    source = path.read_bytes()
    root = parse(source).root_node
    fields = field_types(root, source)
    problems: list[str] = []
    for method in [node for node in walk(root) if node.type in METHODS]:
        names = dict(fields)
        names.update(method_names(method, source))
        returns_float = type_text(method, source) == "float"
        candidates = []
        for node in walk(method):
            if node.type == "local_variable_declaration" and type_text(node, source) == "float":
                for child in node.children:
                    if child.type != "variable_declarator":
                        continue
                    value = child.child_by_field_name("value")
                    name = child.child_by_field_name("name")
                    if value is not None and name is not None:
                        candidates.append((node, value, f"'{text_of(name, source)}'"))
            elif node.type == "assignment_expression":
                left = node.child_by_field_name("left")
                right = node.child_by_field_name("right")
                if left is None or right is None or left.type != "identifier" \
                        or names.get(text_of(left, source)) != FLOAT:
                    continue
                candidates.append((node, right, f"'{text_of(left, source)}'"))
            elif node.type == "return_statement" and returns_float \
                    and enclosing_method(node) is not None and enclosing_method(node).id == method.id:
                value = next((child for child in node.children if child.type not in ("return", ";")),
                             None)
                if value is not None:
                    candidates.append((node, value, "this float return"))
        for where, value, label in candidates:
            if expression_type(value, source, names, constants) != DOUBLE:
                continue
            line = source[:where.start_byte].count(b"\n") + 1
            snippet = re.sub(r"\s+", " ", text_of(value, source)).strip()
            if len(snippet) > 72:
                snippet = snippet[:69] + "..."
            problems.append(f"{path}:{line}: {label} is given {snippet}, which is a double, and no "
                            f"float takes a double without a cast")
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
    """The check has to fail on the line that cost a run, and pass on everything that looks like it."""
    import tempfile

    cases = [
        ("the mistake: a double from a constant into a float", """
class A {
    static final double CHUNK_M = 300.0;
    void run(float[] v, int at, float worst) {
        worst = Math.max(worst, v[at] - CHUNK_M);
    }
}
""", 1),
        ("a double literal into a float", """
class A {
    void run() {
        float half = 0.5;
    }
}
""", 1),
        ("a double local into a float", """
class A {
    void run() {
        double size = 3.0;
        float small = size;
    }
}
""", 1),
        ("Math.sqrt into a float", """
class A {
    void run() {
        float length = Math.sqrt(2.0f);
    }
}
""", 1),
        ("a float return handing back a double", """
class A {
    static float size() {
        return Math.hypot(3.0f, 4.0f);
    }
}
""", 1),
        ("a cast and then a double, which is still a double", """
class A {
    void run(float x) {
        float half = (float) x / 2.0;
    }
}
""", 1),
        ("the cast the author meant", """
class A {
    static final double CHUNK_M = 300.0;
    void run(float[] v, int at, float worst) {
        worst = Math.max(worst, (float) (v[at] - CHUNK_M));
    }
}
""", 0),
        ("a double argument inside a cast, to a Math method", """
class A {
    static final double LIMIT = 2.0;
    float highest(float a, double b) {
        float top = 0f;
        top = Math.max(top, (float) (a * b + LIMIT));
        return top;
    }
}
""", 0),
        ("a double in a comparison, and in a ternary's condition", """
class A {
    static final double CHUNK_M = 300.0;
    float pick(float v, double t) {
        return t <= 0.0 ? v / 8.0f : (float) (v * t);
    }
}
""", 0),
        ("float literals, an int, and a long from Math.round", """
class A {
    void run(float v) {
        float a = 1.0f;
        float b = 0.5f * a;
        float c = 3 / 4;
        float d = Math.round(v) + 1f;
        float e = Math.max(a, 2f);
    }
}
""", 0),
        ("an unknown, which is never reported", """
class A {
    void run(float[] v, int at) {
        float a = v[at];
        float b = neighbours(at);
    }
    float neighbours(int at) { return at * 1f; }
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
            found = len(check_file(path, parse, double_constants([path], parse)))
            ok = (found > 0) == (expected > 0)
            print(f"{'ok   ' if ok else 'FAIL '} selftest '{label}': {found} problem(s)")
            failures += 0 if ok else 1
    return failures


def run(arguments: list[str]) -> int:
    files = targets(arguments)
    parse = parse_source()
    # Constants are collected from the whole tree even when only one file is being checked: a name is
    # proved a double by where it is *declared*, and `ChunkMesher.CHUNK_M` is declared somewhere else.
    # Checking a single file without this finds nothing at all, which is worse than not checking it.
    everywhere = targets(DEFAULT_ROOTS) if arguments else files
    constants = double_constants(sorted(set(files) | set(everywhere)), parse)
    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path, parse, constants))
    print("== narrowing gate ==")
    print(f"scanned {len(files)} Java files, {len(constants)} double constants, {len(problems)} "
          f"problem(s)")
    for problem in problems:
        print(problem)
    print("NARROWING GATE " + ("PASSED" if not problems else "FAILED"))
    return 1 if problems else 0


def main(argv: list[str]) -> int:
    if not argv:
        failures = selftest()
        if failures:
            print(f"NARROWING GATE SELFTEST FAILED ({failures})")
            return 1
        return run(DEFAULT_ROOTS)
    return run(argv)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
