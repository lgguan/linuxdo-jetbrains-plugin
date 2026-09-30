"""Check public proxy APIs in the target IDE, using only synthetic configuration and credentials."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def argfile(path, args):
    path.write_text("\n".join('"' + str(arg).replace("\\", "\\\\").replace('"', '\\"') + '"' for arg in args), encoding="utf-8")
    return "@" + str(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide-home", required=True)
    args = parser.parse_args()
    ide = Path(args.ide_home).resolve()
    if (ide / "Contents/product-info.json").is_file():
        ide /= "Contents"
    info = json.loads((ide / "product-info.json").read_text(encoding="utf-8"))
    output = ROOT / "build/proxy-migration-smoke"
    output.mkdir(parents=True, exist_ok=True)
    archives = list((ROOT / "build/distributions").glob("*.zip"))
    assert len(archives) == 1, "Build exactly one release package first"
    with zipfile.ZipFile(archives[0]) as archive:
        for name in archive.namelist():
            if name.endswith(".jar"):
                target = output / "plugin-libs" / Path(name).name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
    cp = os.pathsep.join(str(p) for p in [output] + list((ide / "lib").glob("*.jar")) + list((output / "plugin-libs").glob("*.jar")))
    executable_suffix = ".exe" if os.name == "nt" else ""
    java = ide / ("jbr/Contents/Home/bin" if os.name != "nt" and (ide / "jbr/Contents").is_dir() else "jbr/bin")
    source = ROOT / "tools/IdeProxyMigrationSmoke.java"
    subprocess.run([str(java / ("javac" + executable_suffix)), argfile(output / "compile.args", ["--release", "17", "-proc:none", "-cp", cp, "-d", output, source])], check=True)
    completed = subprocess.run([str(java / ("java" + executable_suffix)), argfile(output / "run.args", ["-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", cp, "IdeProxyMigrationSmoke"])], capture_output=True, text=True, encoding="utf-8", errors="replace")
    (output / "result.txt").write_text("IDE_BUILD=" + info["buildNumber"] + "\n" + completed.stdout + completed.stderr, encoding="utf-8")
    print(completed.stdout + completed.stderr, end="")
    completed.check_returncode()
    assert "TARGET_IDE_PROXY_MIGRATION_PASS=true" in completed.stdout


if __name__ == "__main__":
    main()
