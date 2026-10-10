import ast
from pathlib import Path
import shutil
import subprocess
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
git = shutil.which('git') or 'C:/Program Files/Git/cmd/git.exe'
bash = 'C:/Program Files/Git/bin/bash.exe' if Path('C:/Program Files/Git/bin/bash.exe').is_file() else shutil.which('bash')
files = subprocess.check_output([git, 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT).decode().split('\0')
checked = 0
for name in sorted(set(files)):
    path = ROOT / name
    if not path.is_file() or name.startswith(('sources/', '.edge-olympiad/')):
        continue
    if path.suffix == '.py':
        ast.parse(path.read_bytes(), filename=name)
    elif path.suffix == '.xml':
        ET.parse(path)
    elif path.suffix == '.toml':
        tomllib.loads(path.read_text(encoding='utf-8'))
    elif path.suffix == '.sh' or name == 'app/gradlew':
        if not bash:
            raise RuntimeError('Bash is required for shell syntax validation')
        subprocess.run([bash, '-O', 'extglob', '-n', str(path)], check=True)
    else:
        continue
    checked += 1
subprocess.run([git, 'diff', '--check'], cwd=ROOT, check=True)
print(f'Source syntax passed for {checked} files')
