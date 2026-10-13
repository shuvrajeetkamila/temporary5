#!/usr/bin/env python3
"""Archive every pushed revision so the project can always be restored, however badly it is broken.

This is deliberately dumb and independent of the rest of the toolchain: it mirrors the repository into
a git bundle (a single file that contains the complete history, all branches and all tags) and stores
it as a GitHub Release asset on a rolling archive tag. A git bundle can restore a full repository:

    git clone game-archive.bundle restored-repo
    git bundle verify game-archive.bundle

Rationale: a force push, a reset, or a bad rewrite can destroy branch history, and nothing in the
repository itself can protect against that. An archive living outside the branch namespace cannot.

Usage (from a checkout with full history):
    python3 tools/repo_archive.py --outdir archives
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def run(cmd: list[str], cwd: Path | None = None, check: bool = True) -> str:
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise SystemExit(f"command failed ({' '.join(cmd)}):\n{result.stderr.strip()}")
    return result.stdout.strip()


def write_bundle(repo: Path, outdir: Path, label: str) -> tuple[Path, Path]:
    outdir.mkdir(parents=True, exist_ok=True)
    bundle = outdir / f"{label}.bundle"
    # --all includes every branch, tag and remote ref, so nothing can be left behind.
    run(["git", "bundle", "create", str(bundle), "--all"], cwd=repo)
    # Verify immediately: an archive that cannot be verified is worse than none. git's wording differs
    # between versions ("The bundle records a complete history." newer, "The bundle is okay" older),
    # so require a clean exit, at least one ref, and no dependency on missing objects.
    heads = run(["git", "bundle", "list-heads", str(bundle)], cwd=repo)
    if not heads.strip():
        raise SystemExit("bundle contains no refs; refusing to publish an empty archive")
    try:
        verify = run(["git", "bundle", "verify", str(bundle)], cwd=repo)
    except SystemExit as exc:
        raise SystemExit(f"bundle verification failed (missing objects?):\n{exc}") from exc
    if "requires these" in verify or "not complete" in verify.lower():
        raise SystemExit(f"bundle is incomplete (it needs refs it does not contain):\n{verify}")
    checksums = outdir / f"{label}.bundle.sha256"
    digest = run(["sha256sum", bundle.name], cwd=outdir).split()[0]
    checksums.write_text(f"{digest}  {bundle.name}\n")
    size_mb = bundle.stat().st_size / (1024 * 1024)
    print(f"wrote {bundle} ({size_mb:.1f} MB), sha256 {digest}")
    print(verify)
    return bundle, checksums


def write_manifest(repo: Path, outdir: Path, label: str, bundle: Path) -> Path:
    head = run(["git", "rev-parse", "HEAD"], cwd=repo)
    branches = run(["git", "branch", "--format=%(refname:short)"], cwd=repo).splitlines()
    tags = run(["git", "tag", "--list"], cwd=repo).splitlines()
    commits = run(["git", "rev-list", "--count", "--all"], cwd=repo)
    manifest = outdir / f"{label}.manifest.txt"
    manifest.write_text(
        "\n".join([
            f"label={label}",
            f"created_utc={datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}",
            f"head={head}",
            f"commits={commits}",
            f"branches={', '.join(branches)}",
            f"tags={', '.join(tags)}",
            f"bundle={bundle.name}",
            f"bundle_bytes={bundle.stat().st_size}",
        ]) + "\n"
    )
    print(manifest.read_text())
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=".", help="repository root")
    parser.add_argument("--outdir", default="archives", help="directory to write archives into")
    parser.add_argument("--label", default=None, help="archive label (default: timestamp + short sha)")
    args = parser.parse_args()

    repo = Path(args.repo).resolve()
    outdir = (repo / args.outdir).resolve()
    if not (repo / ".git").exists():
        print("not a git repository", file=sys.stderr)
        return 2

    if args.label:
        label = args.label
    else:
        sha = run(["git", "rev-parse", "--short", "HEAD"], cwd=repo)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        label = f"game-archive-{stamp}-{sha}"

    bundle, checksums = write_bundle(repo, outdir, label)
    manifest = write_manifest(repo, outdir, label, bundle)

    keep = {bundle.name, checksums.name, manifest.name}
    for stale in outdir.glob("*"):
        if stale.name not in keep and stale.is_file():
            stale.unlink()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
