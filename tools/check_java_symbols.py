#!/usr/bin/env python3
"""A last line of defence for the one Java mistake that keeps getting through.

There is no Java compiler in the development sandbox (the JDK available from PyPI is a runtime, with
no `javac`), so the first thing that can type-check a change is CI, minutes after the push. That is
how a test file that called `assertEquals(...)` seven times without ever importing it reached the
compile gate: the *syntax* was perfect (a tree-sitter parse reports zero errors) and every local check
passed, because local checks cannot resolve names.

This script resolves the names that matter and nothing else. It strips comments, strings and text
blocks, then:

  * fails on an unqualified call to a JUnit assertion/assumption that the file never imported --
    the exact `cannot find symbol` the compiler reports, caught in a second instead of ten minutes;
  * fails on a `import static` whose simple name the file never uses, which is how the missing
    import gets written in the first place: the name is used, so nobody looks at the imports;
  * fails on a `Math.` member the JDK does not have -- `Math.SQRT2` is JavaScript's, and javac
    reported it from the middle of a compile gate rather than from here;
  * fails on an `@Override` that is not sitting on a method, which is what an inserted method
    between an annotation and its method looks like, and which the compiler reports as
    "method does not override or implement a method from a supertype";
  * fails on a type this project declares in *another package* being used without an import, which is
    what `HorizonMesh` was in `GameWorldRenderer` on commit 45dc5a0: the code was correct, the syntax
    was correct, every other local check passed, and javac stopped at `package HorizonMesh does not
    exist` because the file said `HorizonMesh.Horizon` and meant `ai.arena.qatar.render.HorizonMesh`.

It always runs its own embedded samples first -- a missing import, a correct file, a qualified call, a
wildcard import, a file's own helper, an assertion in a comment -- because a checker that has never
been shown a failure is a checker nobody should trust. Run it before pushing:

    python3 tools/check_java_symbols.py
"""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SOURCE_ROOT = REPO / "core" / "src"


def shown(path: Path) -> str:
    """The file's name relative to the repository, or its own path if it came from somewhere else.

    The checker is also pointed at a *previous* revision of a file -- `git show <commit>:<path>` into a
    temporary file -- to prove that a rule catches the bug it was written for. A gate that throws on
    that is a gate that cannot be tested, so this never raises.
    """
    try:
        return str(path.relative_to(REPO))
    except ValueError:
        return str(path)

# Assertion helpers that only exist as statically imported members in this project. If one of these
# is called unqualified, the file must import it (or declare its own, which is handled below).
JUNIT_METHOD = re.compile(r"(?<![\w.$])(assert[A-Za-z0-9_]*|assume[A-Za-z0-9_]*)\s*\(")

STATIC_IMPORT = re.compile(
    r"^\s*import\s+static\s+([A-Za-z_][\w.]*(?:\.\*)?)\s*;\s*$", re.MULTILINE
)

# Every member java.lang.Math actually declares, as of Java 17. Anything else -- and the ones people
# actually reach for, SQRT2, LN2, LOG10E, are the ones JavaScript taught them -- is a compile error.
MATH_MEMBERS = {
    "E", "PI",
    "abs", "acos", "addExact", "asin", "atan", "atan2", "cbrt", "ceil", "copySign", "cos", "cosh",
    "decrementExact", "exp", "expm1", "floor", "floorDiv", "floorMod", "fma", "getExponent", "hypot",
    "IEEEremainder", "incrementExact", "log", "log10", "log1p", "max", "min", "multiplyExact",
    "multiplyFull", "multiplyHigh", "negateExact", "nextAfter", "nextDown", "nextUp", "pow", "random",
    "rint", "round", "scalb", "signum", "sin", "sinh", "sqrt", "subtractExact", "tan", "tanh",
    "toDegrees", "toIntExact", "toRadians", "ulp", "unsignedMultiplyHigh", "ceilDiv", "unsignedAdd",
    "unsignedSubtract", "unsignedMultiply",
}
# The JS-isms, named with what to write instead, so the message is a fix and not only a complaint.
JS_ROOTS = {
    "SQRT2": "Math.sqrt(2.0) -- the square root of two",
    "SQRT1_2": "Math.sqrt(0.5) -- one over the square root of two",
    "LN2": "Math.log(2.0)",
    "LN10": "Math.log(10.0)",
    "LOG2E": "1.0 / Math.log(2.0)",
    "LOG10E": "Math.log10(Math.E)",
}

MATH_MEMBER = re.compile(r"(?<![\w.$])Math\.([A-Za-z_]\w*)")

# Every type this project declares, and the package it declares it in. A file may use a type from
# another package only through a single-type import, a wildcard import, or its own package -- which is
# the rule javac enforces and the one this gate now enforces a second earlier.
# No line anchor and no modifier list: a top-level class, a nested one and a local one all look the
# same to javac, and the `class`/`interface`/`enum`/`record` keyword followed by a capitalised name is
# what a declaration is. `Foo.class` is not, because the keyword there is preceded by a dot.
TYPE_DECLARATION = re.compile(r"(?<![\w.])(?:class|interface|enum|record)\s+([A-Z]\w*)")
PACKAGE_DECLARATION = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
IMPORT_DECLARATION = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", re.MULTILINE)
WILDCARD_IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\.\*\s*;", re.MULTILINE)
# A capitalised identifier in code: a type name, never a variable in this project's style.
CAPITALISED_NAME = re.compile(r"(?<![\w.$])([A-Z]\w*)(?![\w])")


def declared_types(paths) -> dict:
    """A simple name -> the packages that declare a type with that name.

    Only `core/src` is scanned, and only the names the project itself writes: a name that comes from
    libGDX or the JDK is not in this index and is therefore never reported, which is deliberate -- this
    gate is for the imports a change *forgot*, and those are always of code that is in the tree.
    """
    index: dict = {}
    for path in paths:
        text = path.read_text(encoding="utf-8", errors="replace")
        match = PACKAGE_DECLARATION.search(strip_java(text))
        package = match.group(1) if match else ""
        stripped = strip_java(text)
        for name in set(TYPE_DECLARATION.findall(stripped)):
            index.setdefault(name, set()).add(package)
    return index


def own_type_names(source: str) -> set:
    return set(TYPE_DECLARATION.findall(source))


def unresolved_types(source: str, index: dict) -> list:
    """(line, name, packages) for every cross-package type name this file never imports."""
    package_match = PACKAGE_DECLARATION.search(source)
    own_package = package_match.group(1) if package_match else ""
    imports = IMPORT_DECLARATION.findall(source)
    imported = {name.rsplit(".", 1)[-1] for name in imports}
    wildcards = set(WILDCARD_IMPORT.findall(source))
    declared_here = own_type_names(source)
    found: list = []
    for match in CAPITALISED_NAME.finditer(source):
        name = match.group(1)
        packages = index.get(name)
        if not packages:
            continue
        if own_package in packages or name in declared_here or name in imported:
            continue
        if packages & wildcards:
            continue
        found.append((source.count("\n", 0, match.start()) + 1, name, sorted(packages)))
    return found
DRIFTED_OVERRIDE = re.compile(r"^\s*@Override\s*$")
COMMENT_LINE = re.compile(r"^\s*(/\*|\*|//)")


def strip_java(source: str) -> str:
    """Remove comments, string literals and text blocks, keeping one space per removed chunk.

    A brace or a parenthesis inside a string must not reach the structural checks, and a commented
    out `assertTrue(` must not count as a call -- or as a use of an import.
    """
    out: list[str] = []

    def drop(start: int, end: int) -> None:
        # Keep the newlines: the checker reports line numbers, and a comment must not shift them.
        out.append("\n" * source.count("\n", start, end) + " ")

    i = 0
    n = len(source)
    while i < n:
        c = source[i]
        if c == "/" and source.startswith("//", i):
            j = source.find("\n", i)
            drop(i, n if j < 0 else j)
            i = n if j < 0 else j
        elif c == "/" and source.startswith("/*", i):
            j = source.find("*/", i + 2)
            drop(i, n if j < 0 else j + 2)
            i = n if j < 0 else j + 2
        elif source.startswith('"""', i):
            j = source.find('"""', i + 3)
            drop(i, n if j < 0 else j + 3)
            i = n if j < 0 else j + 3
        elif c in "\"'":
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] == c:
                    j += 1
                    break
                if source[j] == "\n":  # unterminated literal: do not swallow the rest of the file
                    break
                j += 1
            drop(i, j)
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def is_declaration(source: str, name: str) -> bool:
    """True when `name(...)` is a method the file declares rather than one it calls.

    A call ends in `);`, a declaration ends in `{`, so the shape of what follows the parameter list
    tells the two apart without needing a type system.
    """
    pattern = re.compile(r"\b" + re.escape(name) + r"\s*\([^;{}]*\)[^;{}]*\{")
    return bool(pattern.search(source))


def static_imports(source: str) -> list[str]:
    return [match.group(1) for match in STATIC_IMPORT.finditer(source)]


def undefined_calls(source: str) -> list[tuple[int, str]]:
    """(line, name) for every JUnit helper called unqualified that the file does not resolve."""
    names = {name.rsplit(".", 1)[-1] for name in static_imports(source)}
    wildcard = any(name.endswith(".*") for name in static_imports(source))
    found: list[tuple[int, str]] = []
    for match in JUNIT_METHOD.finditer(source):
        name = match.group(1)
        # Qualified calls (`Assertions.assertTrue(...)`) resolve through the type, not an import.
        if source[: match.start()].rstrip().endswith("."):
            continue
        if name in names or wildcard or is_declaration(source, name):
            continue
        found.append((source.count("\n", 0, match.start()) + 1, name))
    return found


def unknown_math_members(source: str) -> list[tuple[int, str]]:
    """(line, member) for every `Math.member` that java.lang.Math does not declare."""
    found: list[tuple[int, str]] = []
    for match in MATH_MEMBER.finditer(source):
        member = match.group(1)
        if member not in MATH_MEMBERS:
            found.append((source.count("\n", 0, match.start()) + 1, member))
    return found


def drifted_overrides(raw: str, source: str) -> list[tuple[int, str]]:
    """(line, what follows) for every `@Override` that is not sitting on the method it belongs to.

    An annotation and its method can be separated by comments -- Java's token stream does not care --
    which is exactly how this fails: a method inserted between the two takes the annotation, the
    method it was written for loses it, and the compiler reports "method does not override or
    implement a method from a supertype" minutes into a CI run. Found here from the file's own text,
    three ways:

      * a comment line directly under the annotation (a Javadoc has to come *before* the annotation
        to document the method -- between the two, it documents nothing and the annotation has moved);
      * a following line with no parameter list, which cannot be a method;
      * a following line that declares the method `private`, which can never override anything.

    `raw` is the file's own text, `source` the same text with comments removed.
    """
    found: list[tuple[int, str]] = []
    raw_lines = raw.split("\n")
    for index, line in enumerate(raw_lines):
        if not DRIFTED_OVERRIDE.match(line):
            continue
        following = index + 1
        while following < len(raw_lines) and not raw_lines[following].strip():
            following += 1
        text = raw_lines[following].strip() if following < len(raw_lines) else ""
        if COMMENT_LINE.match(text):
            found.append((index + 1, text[:60]))
    lines = source.split("\n")
    for index, line in enumerate(lines):
        if not DRIFTED_OVERRIDE.match(line):
            continue
        following = index + 1
        while following < len(lines) and not lines[following].strip():
            following += 1
        text = lines[following].strip() if following < len(lines) else ""
        while text.startswith("@") and following + 1 < len(lines):
            following += 1
            while following < len(lines) and not lines[following].strip():
                following += 1
            text = lines[following].strip() if following < len(lines) else ""
        if "(" not in text or ")" not in text:
            found.append((index + 1, text[:60]))
        elif "private" in text:
            found.append((index + 1, text[:60]))
    return found


def check_file(path: Path, index: dict | None = None) -> list[str]:
    raw_source = path.read_text(encoding="utf-8", errors="replace")
    source = strip_java(raw_source)
    problems = []
    if index:
        package_match = PACKAGE_DECLARATION.search(source)
        this_package = package_match.group(1) if package_match else "?"
        for line, name, packages in unresolved_types(source, index):
            problems.append(
                shown(path) + ":" + str(line) + ": `" + name + "` is a type this project declares in `"
                + packages[0] + "`, but this file is in `" + this_package + "` and never imports it: "
                + "javac stops here with \"package " + name + " does not exist\" or \"cannot find "
                + "symbol\" -- add `import " + packages[0] + "." + name + ";`"
            )
    problems.extend([
        f"{shown(path)}:{line}: {name}(...) is called unqualified but was never "
        f"imported -- add `import static org.junit.jupiter.api.Assertions.{name};` or qualify "
        f"the call, because the compiler will stop here"
        for line, name in undefined_calls(source)
    ])

    problems.extend(
        f"{shown(path)}:{line}: java.lang.Math does not declare `{member}`"
        + (f" -- `Math.{member}` is JavaScript's, not Java's: write {JS_ROOTS[member]}"
           if member in JS_ROOTS else " -- check the name against the JDK")
        for line, member in unknown_math_members(source)
    )
    problems.extend(
        f"{shown(path)}:{line}: `@Override` is not sitting on the method it belongs to "
        f"(the next line is `{text}`): the compiler reports it as \"method does not override or "
        f"implement a method from a supertype\", and a method inserted between an annotation and its "
        f"method -- or an annotation left on a private method -- is how that happens"
        for line, text in drifted_overrides(raw_source, source)
    )

    used = STATIC_IMPORT.sub(" ", source)
    for name in sorted(static_imports(source)):
        simple = name.rsplit(".", 1)[-1]
        if simple in ("", "*"):
            continue
        if not re.search(r"(?<![\w.])" + re.escape(simple) + r"(?![\w])", used):
            problems.append(
                f"{shown(path)}: `import static {name};` is never used in this file"
            )
    return problems


def selftest() -> int:
    """Prove the checker fails on the real bug and passes on correct code, before trusting it."""
    broken = "import static org.junit.jupiter.api.Assertions.assertTrue;\n" \
             "class T { void t() { assertTrue(1 == 1, \"ok\"); assertEquals(1, 2, \"no\"); } }\n"
    correct = "import static org.junit.jupiter.api.Assertions.assertEquals;\n" \
              "import static org.junit.jupiter.api.Assertions.assertTrue;\n" \
              "class T { void t() { assertTrue(1 == 1, \"ok\"); assertEquals(1, 1, \"yes\"); } }\n"
    qualified = "import org.junit.jupiter.api.Assertions;\n" \
                "class T { void t() { Assertions.assertEquals(1, 1); } }\n"
    wildcard = "import static org.junit.jupiter.api.Assertions.*;\n" \
               "class T { void t() { assertEquals(1, 1); } }\n"
    helper = "class T { static void assertNear(double a, double b) { }\n" \
             "          void t() { assertNear(1, 2); } }\n"
    commented = "class T { void t() { /* assertTrue(x); */ int y = 1; } }\n"

    # The two mistakes this gate learned in round nine, both of which reached CI.
    js_root = "class T { double r = Math.hypot(3 * Math.SQRT2, 4); }\n"
    real_math = "class T { double r = Math.hypot(3 * Math.sqrt(2.0), Math.toRadians(4)); }\n"
    drifted = "class T {\n    @Override\n    /** doc */\n    void f() { }\n}\n"
    in_place = "class T {\n    @Override\n    public String toString() { return \"t\"; }\n}\n"
    also_in_place = "class T {\n    @Override\n    @Deprecated\n    public int hashCode() { return 1; }\n}\n"
    private_override = "class T {\n    @Override\n    private void f() { }\n}\n"
    no_method = "class T {\n    @Override\n    int field = 1;\n}\n"

    failures = 0
    # The mistake the compile gate caught on 45dc5a0: a type used from another package with no import.
    index = {"HorizonMesh": {"ai.arena.qatar.render"}, "Horizon": {"ai.arena.qatar.render"}}
    missing_import = "package ai.arena.qatar.game;\n" \
                     "class T { HorizonMesh.Horizon h; void f() { HorizonMesh.build(); } }\n"
    with_import = "package ai.arena.qatar.game;\n" \
                  "import ai.arena.qatar.render.HorizonMesh;\n" \
                  "class T { HorizonMesh.Horizon h; void f() { HorizonMesh.build(); } }\n"
    wildcard_import = "package ai.arena.qatar.game;\n" \
                      "import ai.arena.qatar.render.*;\n" \
                      "class T { HorizonMesh.Horizon h; }\n"
    same_package = "package ai.arena.qatar.render;\n" \
                   "class T { HorizonMesh.Horizon h; }\n"
    own_declaration = "package ai.arena.qatar.game;\n" \
                      "class T { static class HorizonMesh { } HorizonMesh h; }\n"
    other_package_var = "package ai.arena.qatar.game;\n" \
                        "class T { void f() { android.horizonMesh = 1; } }\n"
    for label, text, expected in [
        ("a type used from another package with no import", missing_import, 2),
        ("the same with the import", with_import, 0),
        ("a wildcard import of its package", wildcard_import, 0),
        ("a type in the file's own package", same_package, 0),
        ("the file's own nested type", own_declaration, 0),
        ("a qualified reference through another name", other_package_var, 0),
    ]:
        found = unresolved_types(strip_java(text), index)
        if len(found) != expected:
            print(f"FAIL  selftest '{label}': expected {expected}, got {found}")
            failures += 1
        else:
            print(f"ok    selftest '{label}': {len(found)} unresolved type(s)")

    cases = [
        ("a missing assertion import", broken, ["assertEquals"]),
        ("a correct file", correct, []),
        ("qualified calls", qualified, []),
        ("a wildcard import", wildcard, []),
        ("the file's own helper", helper, []),
        ("an assertion inside a comment", commented, []),
    ]
    for label, text, expected in cases:
        found = [name for _, name in undefined_calls(strip_java(text))]
        if found != expected:
            print(f"FAIL  selftest '{label}': expected {expected}, got {found}")
            failures += 1
        else:
            print(f"ok    selftest '{label}': {len(found)} undefined call(s)")

    math_cases = [
        ("a JavaScript root", js_root, ["SQRT2"]),
        ("real java.lang.Math members", real_math, []),
    ]
    for label, text, expected in math_cases:
        found = [member for _, member in unknown_math_members(strip_java(text))]
        if found != expected:
            print(f"FAIL  selftest '{label}': expected {expected}, got {found}")
            failures += 1
        else:
            print(f"ok    selftest '{label}': {len(found)} unknown Math member(s)")

    override_cases = [
        ("an @Override left on a comment", drifted, 1),
        ("an @Override on its method", in_place, 0),
        ("an @Override with a second annotation", also_in_place, 0),
        ("an @Override on a private method", private_override, 1),
        ("an @Override on a field", no_method, 1),
    ]
    for label, text, expected in override_cases:
        found = drifted_overrides(text, strip_java(text))
        if len(found) != expected:
            print(f"FAIL  selftest '{label}': expected {expected}, got {found}")
            failures += 1
        else:
            print(f"ok    selftest '{label}': {len(found)} drifted @Override(s)")
    return failures


def main() -> int:
    print("== java symbol gate ==")
    failures = selftest()
    print("== source tree ==")
    files = sorted(SOURCE_ROOT.rglob("*.java")) if SOURCE_ROOT.is_dir() else []
    index = declared_types(files)
    print(f"    {len(index)} type names declared in the tree, checked against every file's imports")
    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path, index))
    for problem in problems:
        print("FAIL ", problem)
    if not files:
        print("FAIL  no Java sources found under", SOURCE_ROOT)
        failures += 1
    else:
        print(f"scanned {len(files)} Java files, {len(problems)} unresolved name(s)")
    print()
    if failures or problems:
        print(f"JAVA SYMBOL GATE FAILED ({failures + len(problems)} problem(s))")
        return 1
    print("JAVA SYMBOL GATE PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
