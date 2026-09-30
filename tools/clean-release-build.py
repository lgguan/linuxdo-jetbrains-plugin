"""Build the exact working sources in a fresh directory and compare release ZIPs.

Does not change the working Git index or require committing existing user changes.
The source manifest records the snapshot used (including uncommitted sources).
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "build" / "release-check" / str(uuid.uuid4())
INPUTS = (".gitattributes", ".gitignore", ".github", "README.md", "LICENSE",
          "THIRD-PARTY-NOTICES.md", "CHANGELOG.md", "build.gradle.kts",
          "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
          "gradle", "src", "tools", "docs")


def safe(path):
    return not path.is_symlink() and not any(
        part in (".env", ".envrc", ".secrets", "__pycache__") or
        part.startswith(".env.") or part.endswith(".env")
        for part in path.relative_to(ROOT).parts)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    source = DEST / "source"
    source.mkdir(parents=True)
    manifest = {}
    for name in INPUTS:
        item = ROOT / name
        for path in sorted(item.rglob("*") if item.is_dir() else [item]):
            if not safe(path) or not path.is_file():
                continue
            rel = path.relative_to(ROOT)
            target = source / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
            manifest[rel.as_posix()] = digest(path)
    (DEST / "source-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    command = [str(source / "gradlew.bat")] if os.name == "nt" else ["sh", str(source / "gradlew")]
    subprocess.run(command + ["test", "buildPlugin", "verifyPlugin", "--offline", "--console=plain"], cwd=source, check=True)
    packages = list((source / "build/distributions").glob("*.zip"))
    assert len(packages) == 1, "Expected exactly one release ZIP"
    package = packages[0]
    checksum = digest(package)
    (DEST / (package.name + ".sha256")).write_text(checksum + "  " + package.name + "\n", encoding="ascii")
    existing = ROOT / "build/distributions" / package.name
    if existing.is_file():
        assert digest(existing) == checksum, "Fresh-build ZIP differs from workspace ZIP"
    print("CLEAN_SOURCE_BUILD=" + str(DEST), flush=True)
    print("ZIP_SHA256=" + checksum, flush=True)


if __name__ == "__main__":
    main()
