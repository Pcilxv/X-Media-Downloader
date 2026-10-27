from pathlib import Path
import hashlib
import os
import zipfile

root = Path(__file__).resolve().parent.parent
out = root / '交付' / 'X媒体下载-0.1.5-源码.zip'
skip_dirs = {'_devtools', '.gradle', '.kotlin', 'build', '签名密钥', '交付', '验证', '.git', '__pycache__'}
skip_files = {'local.properties'}
paths = []
for current, dirs, files in os.walk(root):
    dirs[:] = [d for d in dirs if d not in skip_dirs]
    for name in files:
        path = Path(current) / name
        relative = path.relative_to(root)
        if name in skip_files or path.suffix.lower() in {'.jks', '.keystore', '.apk'}:
            continue
        if relative.parts[0] == '界面预览' and name.startswith(('模拟器-', '正式包-')):
            continue
        paths.append((path, relative))
out.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(out, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path, relative in sorted(paths, key=lambda pair: str(pair[1])):
        archive.write(path, relative.as_posix())
with zipfile.ZipFile(out) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    assert all('签名密钥/' not in name and not name.endswith(('.jks', '.keystore')) for name in names)
    assert 'app/src/main/AndroidManifest.xml' in names
print('files:', len(paths))
print('sha256:', hashlib.sha256(out.read_bytes()).hexdigest())
print('source archive:', out)
