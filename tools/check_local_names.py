#!/usr/bin/env python3
"""Rejects Java files that declare the same local variable twice in one scope.

    python3 tools/check_local_names.py [files or directories]

Why this exists
---------------

There is no `javac` in the environment this project is developed in, and the CI job that
does have one takes about six minutes to report what it found. On the round that introduced
the walking animation, a test declared `contact` twice in one method: the compiler rejected
the file, the whole test task failed, and the run's six minutes produced one line about a
variable name. The Java symbol gate catches missing imports; the tree-sitter pass catches
syntax errors; this catches the third thing that only javac used to catch.

Java's rule, which this implements: a local variable may not shadow another local variable
that is already in scope, and the set of variables in scope at a declaration is exactly the
declarations in the blocks enclosing it -- its own block and every block between it and the
method. Declarations in *sibling* blocks are fine (two loops each with an `i`), which is why
the check walks the parse tree instead of counting names in a function.

Exit code 0 when nothing is wrong, 1 when something is, with the file, line and name.
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

DECLARATIONS = {"local_variable_declaration"}
# Blocks that can hold a declaration and bound a scope.
SCOPES = {"block", "for_statement", "enhanced_for_statement", "while_statement", "do_statement",
          "if_statement", "switch_block", "switch_expression", "try_statement", "catch_clause",
          "finally_clause", "lambda_expression", "constructor_body", "method_declaration",
          "static_initializer", "resource_specification"}
METHODS = {"method_declaration", "constructor_declaration", "static_initializer", "lambda_expression"}
PARAMETERS = {"formal_parameter", "spread_parameter", "catch_formal_parameter"}


def parser():
    language = tree_sitter.Language(tree_sitter_java.language())
    return tree_sitter.Parser(language)


def declaring_scope(node):
    """The scope a name is introduced into: the nearest enclosing block or method node."""
    current = node.parent
    while current is not None and current.type not in SCOPES and current.type not in METHODS:
        current = current.parent
    return current


def visible_scopes(node):
    """Every scope whose variables are in scope at this node: its block and every block outside it."""
    scopes = []
    current = node.parent
    while current is not None:
        if current.type in SCOPES or current.type in METHODS:
            scopes.append(current)
        if current.type in METHODS:
            break
        current = current.parent
    return scopes


def declaration_nodes(tree):
    """Every node that introduces a local name: a local variable, a parameter, a catch variable."""
    found = []
    stack = [tree.root_node]
    while stack:
        node = stack.pop()
        if node.type in DECLARATIONS or node.type in PARAMETERS:
            found.append(node)
        stack.extend(node.children)
    return found


def names_of(node, source: bytes) -> list[tuple[str, int]]:
    """The names this declaration introduces, with their 1-based lines."""
    names = []
    for child in node.children:
        if child.type == "variable_declarator" or child.type == "formal_parameter" \
                or child.type == "spread_parameter" or child.type == "catch_formal_parameter":
            name = child.child_by_field_name("name")
            if name is not None:
                names.append((source[name.start_byte:name.end_byte].decode("utf-8", "replace"),
                              source[:name.start_byte].count(b"\n") + 1))
    if not names:
        # A catch clause or an enhanced for holds its variable directly.
        name = node.child_by_field_name("name")
        if name is not None:
            names.append((source[name.start_byte:name.end_byte].decode("utf-8", "replace"),
                          source[:name.start_byte].count(b"\n") + 1))
    return names


def check_file(path: Path, parse) -> list[str]:
    source = path.read_bytes()
    tree = parse(source)
    nodes = declaration_nodes(tree)
    problems = []

    # A declaration is a redefinition when another declaration of the same name was introduced into a
    # scope that is still visible where this one appears. Java's rule is about scope, not about order
    # or about counting names: two loops may each declare an `i`, a loop may not redeclare the
    # enclosing method's `i`, and a local may not reuse a parameter's name.
    for node in nodes:
        # tree-sitter node objects are wrappers created on demand, so `id()` of one is not stable --
        # two wrappers for the same node can have different addresses once the first is collected.
        # The binding's own `node.id` is the underlying tree-sitter node id, which is.
        visible = {scope.id for scope in visible_scopes(node)}
        my_scope = declaring_scope(node)
        for name, line in names_of(node, source):
            for other in nodes:
                other_scope = declaring_scope(other)
                if other is node or other_scope is None or other_scope.id not in visible:
                    continue
                if other.start_byte >= node.start_byte:
                    # The scope of a local begins where it is declared, so a name introduced *later*
                    # in an enclosing block is not a conflict: `if (a) { int n = 1; }` and then
                    # `int n = 2;` after the if is legal Java and appears all over this codebase.
                    # Each conflicting pair is still reported once, from the later declaration's side.
                    continue
                for other_name, other_line in names_of(other, source):
                    if other_name == name:
                        problems.append(f"{path}:{line}: '{name}' is already declared at line "
                                        f"{other_line} and is still in scope here")
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
    """The check has to fail on the mistake it exists for, and pass on legal Java."""
    import tempfile

    cases = [
        ("a duplicate in one block", """
class A {
    void run() {
        int contact = 1;
        int contact = 2;
    }
}
""", 1),
        ("the same name in sibling blocks", """
class A {
    void run() {
        for (int i = 0; i < 3; i++) { int x = i; }
        for (int i = 0; i < 3; i++) { int x = i; }
    }
}
""", 0),
        ("an inner block reusing an outer name", """
class A {
    void run() {
        int total = 0;
        if (total == 0) { int total = 1; }
    }
}
""", 1),
        ("a field and a local with the same name", """
class A {
    private int name = 0;
    void run() { int name = 1; }
}
""", 0),
        ("a parameter reused as a local", """
class A {
    void run(int value) { int value = 2; }
}
""", 1),
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


def parse_source():
    java_parser = parser()

    def parse(source: bytes):
        return java_parser.parse(source)

    return parse


def main(argv: list[str]) -> int:
    if not argv:
        return selftest() or run(["core/src", "android/src"])
    return run(argv)


def run(arguments: list[str]) -> int:
    files = targets(arguments)
    if not files:
        print("no Java files to check")
        return 0
    parse = parse_source()
    print("== local name gate ==")
    problems = []
    for path in files:
        problems.extend(check_file(path, parse))
    for problem in problems:
        print("  " + problem)
    if problems:
        print(f"LOCAL NAME GATE FAILED: {len(problems)} duplicate declaration(s) across "
              f"{len(files)} file(s)")
        return 1
    print(f"scanned {len(files)} Java files, 0 duplicate declarations")
    print("\nLOCAL NAME GATE PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
