import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
source = (ROOT / 'native/src/core/module.rs').read_text(encoding='utf-8')
start = source.index('fn has_external_module_work(')
end = source.index('\npub fn disable_modules()', start)
production = source[start:end]

with tempfile.TemporaryDirectory(prefix='reisenless-modules-') as directory:
    work = Path(directory)
    module_root = work / 'modules'
    upgrade_root = work / 'modules_update'
    harness = r'''
use std::fs::{self, ReadDir};
use std::io;
use std::path::{Path, PathBuf};
macro_rules! cstr { ($value:expr) => { Path::new($value) }; }
struct Directory { path: PathBuf, entries: ReadDir }
struct Entry { path: PathBuf, name: String }
impl Directory {
    fn open(path: &Path) -> io::Result<Self> {
        Ok(Self { path: PathBuf::from(path), entries: fs::read_dir(path)? })
    }
    fn read(&mut self) -> io::Result<Option<Entry>> {
        self.entries.next().transpose().map(|entry| entry.map(|entry| Entry {
            path: entry.path(), name: entry.file_name().to_string_lossy().into_owned(),
        }))
    }
    fn contains_path(&self, name: &Path) -> bool { self.path.join(name).exists() }
}
impl Entry {
    fn is_dir(&self) -> bool { self.path.is_dir() }
    fn name(&self) -> &str { &self.name }
    fn open_as_dir(&self) -> io::Result<Directory> {
        Directory::open(&self.path)
    }
}
fn main() {
    assert!(!has_external_module_work(false));
    fs::create_dir_all(format!("{MODULEROOT}/.core")).expect("fixture");
    assert!(!has_external_module_work(false));
    fs::write(format!("{MODULEROOT}/stray-file"), b"").expect("fixture");
    assert!(!has_external_module_work(false));
    fs::create_dir_all(format!("{MODULEROOT}/example")).expect("fixture");
    assert!(has_external_module_work(false));
    fs::write(format!("{MODULEROOT}/example/disable"), b"").expect("fixture");
    assert!(!has_external_module_work(false));
    for marker in ["remove", "update"] {
        fs::write(format!("{MODULEROOT}/example/{marker}"), b"").expect("fixture");
        assert!(has_external_module_work(false));
        fs::remove_file(format!("{MODULEROOT}/example/{marker}")).expect("fixture");
    }
    fs::remove_file(format!("{MODULEROOT}/example/disable")).expect("fixture");
    fs::create_dir_all(format!("{MODULEROOT}/example/zygisk")).expect("fixture");
    assert!(!has_external_module_work(false));
    assert!(has_external_module_work(true));
    fs::write(format!("{MODULEROOT}/example/disable"), b"").expect("fixture");
    assert!(!has_external_module_work(true));
    fs::create_dir_all(MODULEUPGRADE).expect("fixture");
    assert!(has_external_module_work(false));
    println!("Module activation regression passed");
}
'''
    harness = (f'const MODULEROOT: &str = {json.dumps(module_root.as_posix())};\n'
               f'const MODULEUPGRADE: &str = {json.dumps(upgrade_root.as_posix())};\n'
               + harness + production)
    test_source = work / 'modules.rs'
    test_source.write_text(harness, encoding='utf-8')
    binary = work / ('module-activation-test.exe' if os.name == 'nt' else 'module-activation-test')
    subprocess.run(['rustc', '--edition=2024', str(test_source), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
