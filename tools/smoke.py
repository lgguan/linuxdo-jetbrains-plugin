#!/usr/bin/env python3
"""Cross-platform JCEF and packaged-plugin checks. Requires Python 3.9+ and a JDK."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "build"
WINDOWS = sys.platform == "win32"
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
GRADLE = ROOT / ("gradlew.bat" if WINDOWS else "gradlew")

def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def run_logged(report, *args):
    command = [str(arg) for arg in args]
    with report.open("w", encoding="utf-8") as log:
        with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding="utf-8", errors="replace") as process:
            for line in process.stdout:
                log.write(line)
                log.flush()
                print(line, end="", flush=True)
            if process.wait() != 0:
                raise subprocess.CalledProcessError(process.returncode, command)


def prepare(online=False, package=False):
    args = [GRADLE, "-I", "tools/smoke.init.gradle", "classes", "writeSmokeClasspath", "--console=plain"]
    if package:
        args.append("buildPlugin")
    if not online:
        args.append("--offline")
    run(*args)
    return (BUILD / "smoke-classpath.txt").read_text().strip()


def ide_layout(path):
    home = Path(path).expanduser().resolve()
    if (home / "Contents/product-info.json").is_file():
        home /= "Contents"
    product = json.loads((home / "product-info.json").read_text(encoding="utf-8"))
    os_name = {"win32": "Windows", "darwin": "macOS"}.get(sys.platform, "Linux")
    def normalize_arch(value):
        return {"amd64": "x86_64", "x64": "x86_64", "arm64": "aarch64"}.get(value.lower(), value.lower())
    arch = normalize_arch(platform.machine())
    launch = next((item for item in product["launch"] if item["os"] == os_name and normalize_arch(item["arch"]) == arch), None)
    if launch is None:
        raise RuntimeError(f"IDE has no launch configuration for {os_name}/{arch}")
    java = (home / launch["javaExecutablePath"]).resolve()
    javac = java.with_name("javac.exe" if WINDOWS else "javac")
    if not javac.is_file():
        javac_path = shutil.which("javac")
        if javac_path is None:
            raise RuntimeError("A JDK with javac matching the IDE's Java version is required")
        javac = Path(javac_path)
    jar = home / "plugins/jcef-plugin/lib/modules/intellij.libraries.jcef.jar"
    if not jar.is_file():
        raise RuntimeError("IDE has no modern bundled JCEF API")
    return home, launch, java, javac, jar


def compile_java(javac, cp, output, *sources):
    output.mkdir(parents=True, exist_ok=True)
    run(javac, "--release", "17", "-proc:none", "-cp", cp, "-d", output,
        *(ROOT / "tools" / (source + ".java") for source in sources))


def private(args):
    cp = prepare(args.online)
    home, launch, java, javac, cef = ide_layout(args.ide_home)
    output = BUILD / "portable-smoke" / str(uuid.uuid4())
    cp = os.pathsep.join([str(output), str(cef), cp])
    compile_java(javac, cp, output, "StandaloneSmokeApplication", "PluginCefSmoke", "PortableJcefSmoke", "PaginationSmoke")
    properties = ["--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8",
                  "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                  f"-Didea.system.path={output / 'system'}", f"-Didea.config.path={output / 'config'}",
                  f"-Didea.log.path={output / 'log'}"]
    # Use the native JNA bundled with the compile SDK that supplies our standalone classpath.
    for entry in cp.split(os.pathsep):
        if Path(entry).name == "util-8.jar":
            jna = Path(entry).parent / "jna" / ("aarch64" if platform.machine().lower() in ("arm64", "aarch64") else "amd64")
            if jna.is_dir():
                properties.append(f"-Djna.boot.library.path={jna}")
            break
    print(f"SMOKE_ROOT={output}", flush=True)
    run_logged(output / "result.txt", java, *properties, "-cp", cp, "PluginCefSmoke" if args.network else "PortableJcefSmoke",
        *([args.doh_url] if args.network else []))


def host(args):
    cp = prepare(args.online, package=not args.plugin_zip)
    home, launch, java, javac, cef = ide_layout(args.ide_home)
    package = Path(args.plugin_zip).resolve() if args.plugin_zip else Path((BUILD / "smoke-plugin-path.txt").read_text().strip())
    output = BUILD / "host-smoke" / str(uuid.uuid4())
    plugins = output / "plugins"
    plugins.mkdir(parents=True)
    # A recognized, empty config prevents first-run migration of personal settings.
    options = output / "config/options"
    options.mkdir(parents=True)
    (options / "ide.general.xml").write_text('<application/>', encoding="utf-8")
    with zipfile.ZipFile(package) as archive:
        for name in archive.namelist():
            if not (plugins / name).resolve().is_relative_to(plugins.resolve()):
                raise RuntimeError("Plugin ZIP contains an unsafe path")
        archive.extractall(plugins)
    classes = output / "test-classes"
    ui = getattr(args, "ui", False)
    starter = "IdeUiSmoke" if ui else "IdePluginSmoke"
    command = "linuxdo-ui-smoke" if ui else "linuxdo-host-smoke"
    compile_java(javac, os.pathsep.join([str(cef), cp]), classes, starter, *([] if ui else ["PluginCefSmoke"]))
    library = plugins / "linuxdo-host-smoke/lib/host-test.jar"
    library.parent.mkdir(parents=True)
    descriptor = ('<idea-plugin><id>linuxdo.host.smoke</id><name>LinuxDo Host Smoke</name>'
                  '<version>1</version><vendor>Local test</vendor><depends>com.intellij.modules.platform</depends>'
                  '<depends>com.lgguan.linuxdo.plugin</depends><extensions defaultExtensionNs="com.intellij">'
                  f'<appStarter id="{command}" implementation="{starter}"/></extensions></idea-plugin>')
    with zipfile.ZipFile(library, "w") as archive:
        archive.writestr("META-INF/plugin.xml", descriptor)
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes).as_posix())
    report = output / "result.txt"
    vm_args = [arg.replace("%IDE_HOME%", str(home)) for arg in launch["additionalJvmArguments"]]
    if getattr(args, "draft_bridge", None):
        vm_args.append(f"-Dlinuxdo.draft.bridge={Path(args.draft_bridge).resolve()}")
    if args.native:
        vm_args += ["-Dlinuxdo.host.native=true", "-Dlinuxdo.host.smoke=true"]
    cp = os.pathsep.join(str(home / "lib" / name) for name in launch["bootClassPathJarNames"])
    print(f"HOST_SMOKE_ROOT={output}", flush=True)
    run(java, *vm_args, f"-Djava.awt.headless={str(not ui).lower()}", "-Didea.is.internal=true", "-Dintellij.startup.wizard=false",
        f"-Didea.home.path={home}", f"-Didea.config.path={output / 'config'}", f"-Didea.system.path={output / 'system'}",
        f"-Didea.log.path={output / 'log'}", f"-Didea.plugins.path={plugins}", f"-Dlinuxdo.host.report={report}",
        "-Didea.trust.all.projects=true", "-Didea.paths.selector=LinuxDoHostSmoke", "-Djb.vmOptionsFile=",
        "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader", "-cp", cp, launch["mainClass"], command)
    result = report.read_text()
    print(result)
    if "ERROR=" in result or ("IDE_UI_PASS=true" if ui else "SUPPORTED=true") not in result or (args.native and "HOST_NATIVE_PASS=true" not in result):
        raise RuntimeError(f"Host smoke failed: {report}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    for mode in ("private", "host", "ui"):
        sub = modes.add_parser(mode)
        sub.add_argument("--online", action="store_true", help="Allow Gradle to download uncached dependencies")
        sub.add_argument("--ide-home", required=True, help="IDE directory or macOS .app")
        if mode == "private":
            sub.add_argument("--network", action="store_true", help="Also access public Linux Do pages (no login submission)")
            sub.add_argument("--doh-url", default="https://ldh.ddd.oaifree.com/query-dns")
        if mode in ("host", "ui"):
            sub.add_argument("--plugin-zip")
            sub.add_argument("--native", action="store_true")
        if mode == "ui":
            sub.add_argument("--draft-bridge", help="Authorized draft-only browser handoff directory")
    args = parser.parse_args()
    args.ui = args.mode == "ui"
    {"private": private, "host": host, "ui": host}[args.mode](args)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        # Do not dump giant classpaths or potentially private command arguments.
        print(f"Smoke command failed (exit={error.returncode}); inspect the reported run directory.", file=sys.stderr)
        sys.exit(error.returncode if error.returncode > 0 else 1)
