#!/usr/bin/env python3
"""A last line of defence for the one Java mistake that keeps getting through.

There is no Java compiler in the development sandbox (the JDK available from PyPI is a runtime, with
no `javac`), so the first thing that can type-check a change is CI, minutes after the push. That is
how a test file that called `assertEquals(...)` seven times without ever importing it reached the
compile gate: the *syntax* was perfect (a tree-sitter parse reports zero errors) and every local check
passed, because local checks cannot resolve names.

This script resolves the names that matter to a test file and nothing else. It strips comments,
strings and text blocks, then:

  * fails on an unqualified call to a JUnit assertion/assumption that the file never imported --
    the exact `cannot find symbol` the compiler reports, caught in a second instead of ten minutes;
  * fails on a `import static` whose simple name the file never uses, which is how the missing
    import gets written in the first place: the name is used, so nobody looks at the imports.

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

# Assertion helpers that only exist as statically imported members in this project. If one of these
# is called unqualified, the file must import it (or declare its own, which is handled below).
JUNIT_METHOD = re.compile(r"(?<![\w.$])(assert[A-Za-z0-9_]*|assume[A-Za-z0-9_]*)\s*\(")

STATIC_IMPORT = re.compile(
    r"^\s*import\s+static\s+([A-Za-z_][\w.]*(?:\.\*)?)\s*;\s*$", re.MULTILINE
)


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


def check_file(path: Path) -> list[str]:
    source = strip_java(path.read_text(encoding="utf-8", errors="replace"))
    problems = [
        f"{path.relative_to(REPO)}:{line}: {name}(...) is called unqualified but was never "
        f"imported -- add `import static org.junit.jupiter.api.Assertions.{name};` or qualify "
        f"the call, because the compiler will stop here"
        for line, name in undefined_calls(source)
    ]

    used = STATIC_IMPORT.sub(" ", source)
    for name in sorted(static_imports(source)):
        simple = name.rsplit(".", 1)[-1]
        if simple in ("", "*"):
            continue
        if not re.search(r"(?<![\w.])" + re.escape(simple) + r"(?![\w])", used):
            problems.append(
                f"{path.relative_to(REPO)}: `import static {name};` is never used in this file"
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

    failures = 0
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
    return failures


def main() -> int:
    print("== java symbol gate ==")
    failures = selftest()
    print("== source tree ==")
    files = sorted(SOURCE_ROOT.rglob("*.java")) if SOURCE_ROOT.is_dir() else []
    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path))
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
