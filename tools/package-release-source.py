"""Package the exact allowlisted working sources, including uncommitted changes."""
import hashlib
import json
import re
import runpy
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
policy = runpy.run_path(str(ROOT/'tools/clean-release-build.py'))
version = re.search(r'version = "([^"]+)"',(ROOT/'build.gradle.kts').read_text(encoding='utf-8')).group(1)
output = ROOT/'build/delivery'
output.mkdir(parents=True,exist_ok=True)
archive = output/f'linuxdo-jetbrains-plugin-{version}-source.zip'
manifest = {}
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as package:
    for name in policy['INPUTS']:
        item = ROOT/name
        for path in sorted(item.rglob('*') if item.is_dir() else [item]):
            if not path.is_file() or not policy['safe'](path):
                continue
            relative = path.relative_to(ROOT).as_posix()
            content = path.read_bytes()
            manifest[relative] = hashlib.sha256(content).hexdigest()
            info = zipfile.ZipInfo(relative,date_time=(1980,1,1,0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            package.writestr(info,content)
(output/f'linuxdo-jetbrains-plugin-{version}-source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
for path in [archive,ROOT/f'build/distributions/linuxdo-jetbrains-plugin-{version}.zip']:
    checksum = hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_name(path.name+'.sha256').write_text(checksum+'  '+path.name+'\n',encoding='ascii')
    print(path.relative_to(ROOT).as_posix()+' SHA256='+checksum)
