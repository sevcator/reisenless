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
