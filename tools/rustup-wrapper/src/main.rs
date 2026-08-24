use std::env;
use std::path::Path;
use std::process::{Command, Stdio};

use home::cargo_home;














fn main() -> std::io::Result<()> {
    let exe = env::args().next().unwrap();
    let exe = Path::new(&exe).file_name().unwrap().to_str().unwrap();
    let real_exe = cargo_home()?.join("bin").join(exe);
    let argv: Vec<String> = env::args().skip(1).collect();

    if exe.starts_with("rustup") && argv.iter().any(|s| s == "component") {
        let status = Command::new(&real_exe)
            .args(&argv)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()?;
        if !status.success() {
            let mut cmd = Command::new(&real_exe);

            cmd.arg("+nightly");

            cmd.args(argv.iter().filter(|s| !s.starts_with('+')));
            return cmd.status().map(|_| ());
        }
    }


    Command::new(&real_exe).args(argv.iter()).status().map(|_| ())
}
