fn selinux_mls_level(context: &str) -> Option<&str> {
    let mut fields = context.splitn(4, ':');
    for _ in 0..3 {
        let field = fields.next()?;
        if field.is_empty()
            || !field
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'_')
        {
            return None;
        }
    }
    let level = fields.next()?;
    let categories = level.strip_prefix("s0:")?;
    fn category(value: &str) -> Option<u16> {
        let value = value.strip_prefix('c')?;
        if value.is_empty() || !value.bytes().all(|c| c.is_ascii_digit()) {
            return None;
        }
        let value: u16 = value.parse().ok()?;
        (value <= 1023).then_some(value)
    }
    let mut previous = None;
    for entry in categories.split(',') {
        let (start, end) = match entry.split_once('.') {
            Some((start, end)) => (category(start)?, category(end)?),
            None => {
                let value = category(entry)?;
                (value, value)
            }
        };
        if end < start || previous.is_some_and(|value| start <= value) {
            return None;
        }
        previous = Some(end);
    }
    Some(level)
}

pub fn selinux_identity_matches(peer_context: &str, data_context: &str) -> bool {
    matches!(
        (
            selinux_mls_level(peer_context),
            selinux_mls_level(data_context),
        ),
        (Some(peer), Some(data)) if peer == data
    )
}

pub fn parse_status_id(status: &str, name: &str) -> Option<i32> {
    let mut entries = status
        .lines()
        .filter_map(|line| line.strip_prefix(name)?.strip_prefix(':'));
    let entry = entries.next()?;
    if entries.next().is_some() {
        return None;
    }
    let mut values = entry.split_whitespace();
    let value = values.next()?;
    if !value.bytes().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let id: i32 = value.parse().ok()?;
    if name == "Uid" {
        // A manager child must retain the same real/effective/saved/fs UID.
        for _ in 0..3 {
            if values.next()? != value {
                return None;
            }
        }
    }
    values.next().is_none().then_some(id)
}

pub fn process_identity_matches(process_uid: Option<i32>, peer_uid: i32) -> bool {
    process_uid == Some(peer_uid)
}

pub fn uid_owners_are_exclusive(owners: &[(String, i32)], package: &str, uid: i32) -> bool {
    let mut found = false;
    for (owner, owner_uid) in owners {
        if *owner_uid != uid {
            continue;
        }
        if owner != package || found {
            return false;
        }
        found = true;
    }
    found
}

pub fn manager_identity_matches(
    peer_uid: i32,
    package_uid: i32,
    certificate_matches: bool,
    peer_context: &str,
    data_context: &str,
) -> bool {
    (10_000..20_000).contains(&(peer_uid % 100_000))
        && certificate_matches
        && peer_uid == package_uid
        && selinux_identity_matches(peer_context, data_context)
}

pub fn privileged_client_authorized(
    peer_uid: i32,
    manager_uid: Option<i32>,
    certificate_matches: bool,
    peer_context: &str,
    data_context: &str,
    process_uid: Option<i32>,
) -> bool {
    if peer_uid == 0 {
        return true;
    }
    let Some(manager_uid) = manager_uid else {
        return false;
    };
    manager_identity_matches(
        peer_uid,
        manager_uid,
        certificate_matches,
        peer_context,
        data_context,
    ) && process_identity_matches(process_uid, peer_uid)
}

#[cfg(test)]
mod tests {
    use super::{
        manager_identity_matches, parse_status_id, privileged_client_authorized,
        process_identity_matches, selinux_identity_matches, selinux_mls_level,
        uid_owners_are_exclusive,
    };

    #[test]
    fn extracts_only_categorized_selinux_mls_identity() {
        assert_eq!(
            selinux_mls_level("u:r:untrusted_app:s0:c123,c456"),
            Some("s0:c123,c456")
        );
        assert_eq!(
            selinux_mls_level("u:object_r:app_data_file:s0:c123,c456"),
            Some("s0:c123,c456")
        );
        assert_eq!(selinux_mls_level("u:r:untrusted_app:s0"), None);
        assert_eq!(selinux_mls_level("invalid"), None);
    }

    #[test]
    fn rejects_malformed_mcs_even_when_both_labels_are_identical() {
        for level in [
            "stuff:c1", "s0:nope", "s0:c", "s0:c1024", "s0:c2,c1", "s0:c1,c1", "s0:c1,",
            "s0:c1:c2", "s0:c4.c2", "s0:c+1",
        ] {
            assert!(
                !selinux_identity_matches(
                    &format!("u:r:untrusted_app:{level}"),
                    &format!("u:object_r:app_data_file:{level}"),
                ),
                "accepted {level}"
            );
        }
        assert!(selinux_identity_matches(
            "u:r:untrusted_app:s0:c1.c3,c5",
            "u:object_r:app_data_file:s0:c1.c3,c5",
        ));
    }

    #[test]
    fn rejects_malformed_or_ambiguous_process_credentials() {
        for status in [
            "UidBogus: 10123",
            "Uid:: 10123",
            "Uid: -1",
            "Uid: +10123",
            "Uid: 10123",
            "Uid: 10123 0 10123 10123",
            "Uid: 10123 10123 10123 10123 extra",
            "Uid: 10123 10123 10123 10123\nUid: 10123 10123 10123 10123",
        ] {
            assert_eq!(parse_status_id(status, "Uid"), None, "accepted {status}");
        }
    }

    #[test]
    fn matches_manager_process_to_its_data_category() {
        assert!(selinux_identity_matches(
            "u:r:untrusted_app:s0:c123,c456",
            "u:object_r:app_data_file:s0:c123,c456",
        ));
        assert!(!selinux_identity_matches(
            "u:r:untrusted_app:s0:c123,c456",
            "u:object_r:app_data_file:s0:c321,c654",
        ));
        assert!(!selinux_identity_matches(
            "u:r:untrusted_app:s0",
            "u:object_r:app_data_file:s0",
        ));
    }

    #[test]
    fn parses_kernel_process_credentials() {
        let status = "Name:\ttest\nUid:\t10123\t10123\t10123\t10123\nPPid:\t321\n";
        assert_eq!(parse_status_id(status, "Uid"), Some(10123));
        assert_eq!(parse_status_id(status, "PPid"), Some(321));
        assert_eq!(parse_status_id(status, "Gid"), None);
    }

    #[test]
    fn requires_uid_certificate_and_selinux_identity() {
        let peer = "u:r:untrusted_app:s0:c123,c456";
        let data = "u:object_r:app_data_file:s0:c123,c456";
        assert!(manager_identity_matches(10123, 10123, true, peer, data));
        assert!(!manager_identity_matches(10124, 10123, true, peer, data));
        assert!(!manager_identity_matches(10123, 10123, false, peer, data));
        assert!(!manager_identity_matches(
            10123,
            10123,
            true,
            peer,
            "u:object_r:app_data_file:s0:c321,c654",
        ));
        assert!(!manager_identity_matches(
            110123,
            10123,
            true,
            "u:r:untrusted_app:s0:c123,c456",
            data,
        ));
    }

    #[test]
    fn binds_socket_pid_to_kernel_peer_uid() {
        assert!(process_identity_matches(Some(10123), 10123));
        assert!(!process_identity_matches(Some(10124), 10123));
        assert!(!process_identity_matches(None, 10123));
    }

    #[test]
    fn rejects_missing_duplicate_and_shared_uid_owners() {
        let package = "com.example.manager";
        assert!(uid_owners_are_exclusive(
            &[
                (package.to_string(), 10123),
                ("com.example.other".into(), 10124)
            ],
            package,
            10123,
        ));
        assert!(!uid_owners_are_exclusive(&[], package, 10123));
        assert!(!uid_owners_are_exclusive(
            &[
                (package.to_string(), 10123),
                ("com.example.shared".into(), 10123)
            ],
            package,
            10123,
        ));
        assert!(!uid_owners_are_exclusive(
            &[(package.to_string(), 10123), (package.to_string(), 10123)],
            package,
            10123,
        ));
    }

    #[test]
    fn authorizes_only_root_or_the_bound_signed_manager() {
        let peer = "u:r:untrusted_app:s0:c123,c456";
        let data = "u:object_r:app_data_file:s0:c123,c456";

        assert!(privileged_client_authorized(
            110123,
            Some(110123),
            true,
            peer,
            data,
            Some(110123)
        ));
        for uid in [2000, 99999, -1] {
            assert!(!privileged_client_authorized(
                uid,
                Some(uid),
                true,
                peer,
                data,
                Some(uid)
            ));
        }

        assert!(privileged_client_authorized(
            10123,
            Some(10123),
            true,
            peer,
            data,
            Some(10123)
        ));
        assert!(privileged_client_authorized(
            0,
            None,
            false,
            "malformed",
            "",
            None
        ));
        assert!(!privileged_client_authorized(
            2000,
            Some(10123),
            true,
            peer,
            data,
            Some(2000)
        ));
        assert!(!privileged_client_authorized(
            10124,
            Some(10123),
            true,
            peer,
            data,
            Some(10124)
        ));
        assert!(!privileged_client_authorized(
            10123,
            Some(10123),
            false,
            peer,
            data,
            Some(10123)
        ));
        assert!(!privileged_client_authorized(
            10123,
            None,
            false,
            peer,
            data,
            Some(10123)
        ));
        assert!(!privileged_client_authorized(
            10123,
            Some(10124),
            true,
            peer,
            data,
            Some(10123)
        ));
        assert!(!privileged_client_authorized(
            110123,
            Some(10123),
            true,
            peer,
            data,
            Some(110123)
        ));
        assert!(!privileged_client_authorized(
            10123,
            Some(10123),
            true,
            "malformed",
            data,
            None
        ));
    }
}
