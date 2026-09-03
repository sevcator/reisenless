#[cfg(not(test))]
use super::UDONGE_ROOT;
#[cfg(test)]
const UDONGE_ROOT: &str = "";

fn base_package(process: &str) -> &str {
    let package = process
        .split_once(':')
        .map_or(process, |(package, _)| package);
    package.strip_suffix("_zygote").unwrap_or(package)
}

fn target_config_contains(config: &str, package: &str) -> bool {
    config.lines().any(|line| {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            return false;
        }
        line.strip_prefix("stealth:").unwrap_or(line) == package
    })
}

fn is_application_uid(uid: i32) -> bool {
    uid.rem_euclid(100_000) >= 10_000
}

/// Returns whether Udonge is eligible to enter a denylisted process.
///
/// This policy deliberately lives in the Udonge source tree. Reisenless only
/// consumes the boolean at its built-in-module transport boundary.
pub fn should_load(uid: i32, process: &str) -> bool {
    let package = base_package(process);
    // Manager hiding is mandatory, so every ordinary application UID needs
    // the tiny built-in filter. This avoids reading hideapps.conf during every
    // app specialization. The manager is excluded by ProcessIsMagiskApp and
    // Udonge binds policy to Android's system-provided app data directory.
    if is_application_uid(uid) {
        return true;
    }
    if !std::path::Path::new(&format!("{UDONGE_ROOT}/state/enabled")).exists()
        || std::path::Path::new(&format!("{UDONGE_ROOT}/state/disabled")).exists()
    {
        return false;
    }
    if process == "com.google.android.gms.unstable" {
        return true;
    }
    let targets =
        std::fs::read_to_string(format!("{UDONGE_ROOT}/state/targets.conf")).unwrap_or_default();
    if target_config_contains(&targets, package) {
        return true;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::{base_package, is_application_uid, target_config_contains};

    #[test]
    fn every_android_application_uid_uses_the_builtin_filter() {
        assert!(is_application_uid(10_000));
        assert!(is_application_uid(110_000));
        assert!(is_application_uid(99_000));
        assert!(!is_application_uid(9_999));
        assert!(!is_application_uid(100_000));
    }

    #[test]
    fn target_config_accepts_exact_normal_and_stealth_entries() {
        let config = "# comment\ncom.example.normal\nstealth:com.example.stealth\n";
        assert!(target_config_contains(config, "com.example.normal"));
        assert!(target_config_contains(config, "com.example.stealth"));
        assert!(!target_config_contains(config, "com.example"));
    }

    #[test]
    fn process_suffix_is_not_part_of_package_identity() {
        assert_eq!(base_package("com.example.app:worker"), "com.example.app");
        assert_eq!(base_package("com.example.app_zygote"), "com.example.app");
    }
}
