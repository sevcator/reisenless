#[path = "../native/src/core/apk_cert.rs"]
mod apk_cert;

use std::env;
use std::fs::File;
use std::io::{self, Write};
use std::path::Path;

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 3 {
        eprintln!("Usage: {} <versionCode> <apk_path>", args[0]);
        std::process::exit(1);
    }
    let version = args[1].parse::<i32>().unwrap_or(0);
    let apk_path = Path::new(&args[2]);
    let mut file = match File::open(apk_path) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("Failed to open APK file: {e}");
            std::process::exit(1);
        }
    };
    match apk_cert::read_certificate(&mut file, version) {
        Ok(cert) => {
            io::stdout().write_all(&cert).unwrap();
        }
        Err(e) => {
            eprintln!("Failed to read certificate from APK: {e}");
            std::process::exit(1);
        }
    }
}
