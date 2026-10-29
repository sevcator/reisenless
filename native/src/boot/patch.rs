use base::{LoggedResult, MappedFile, MutBytesExt, Utf8CStr};


macro_rules! match_patterns {
    ($buf:ident, $($str:literal), *) => {{
        let mut len = if *$buf.get_unchecked(0) == b',' { 1 } else { 0 };
        let b = $buf.get_unchecked(len..);
        let found = if b.is_empty() {
            false
        }
        $(
        else if b.starts_with($str) {
            len += $str.len();
            true
        }
        )*
        else {
            false
        };
        if found {
            let b = $buf.get_unchecked(len..);
            if !b.is_empty() && b[0] == b'=' {
                for c in b.iter() {
                    if b" \n\0".contains(c) {
                        break;
                    }
                    len += 1;
                }
            }
            Some(len)
        } else {
            None
        }
    }};
}

fn remove_pattern(buf: &mut [u8], pattern_matcher: unsafe fn(&[u8]) -> Option<usize>) -> usize {
    let mut write = 0_usize;
    let mut read = 0_usize;
    let mut sz = buf.len();

    unsafe {
        while read < buf.len() {
            if let Some(len) = pattern_matcher(buf.get_unchecked(read..)) {
                let skipped = buf.get_unchecked(read..(read + len));

                let skipped = String::from_utf8_lossy(skipped);
                eprintln!("Remove pattern [{skipped}]");
                sz -= len;
                read += len;
            } else {
                *buf.get_unchecked_mut(write) = *buf.get_unchecked(read);
                write += 1;
                read += 1;
            }
        }
    }
    if let Some(buf) = buf.get_mut(write..) {
        buf.fill(0);
    }
    sz
}

pub fn patch_verity(buf: &mut [u8]) -> usize {
    unsafe fn match_verity_pattern(buf: &[u8]) -> Option<usize> {
        unsafe {
            match_patterns!(
                buf,
                b"verifyatboot",
                b"verify",
                b"avb_keys",
                b"avb",
                b"support_scfs",
                b"fsverity"
            )
        }
    }

    remove_pattern(buf, match_verity_pattern)
}

pub fn patch_encryption(buf: &mut [u8]) -> usize {
    unsafe fn match_encryption_pattern(buf: &[u8]) -> Option<usize> {
        unsafe { match_patterns!(buf, b"forceencrypt", b"forcefdeorfbe", b"fileencryption") }
    }

    remove_pattern(buf, match_encryption_pattern)
}

fn hex2byte(hex: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(hex.len() / 2);
    for bytes in hex.chunks(2) {
        if bytes.len() != 2 {
            break;
        }
        let parse_nibble = |b: u8| match b {
            b'0'..=b'9' => b - b'0',
            b'a'..=b'f' => b - b'a' + 10,
            b'A'..=b'F' => b - b'A' + 10,
            _ => 0,
        };
        let h = parse_nibble(bytes[0]);
        let l = parse_nibble(bytes[1]);
        v.push((h << 4) | l);
    }
    v
}

pub fn hexpatch(file: &Utf8CStr, from: &Utf8CStr, to: &Utf8CStr) -> bool {
    let res = || -> LoggedResult<bool> {
        let mut map = MappedFile::open_rw(file)?;
        let pattern = hex2byte(from.as_bytes());
        let patch = hex2byte(to.as_bytes());

        let v = map.patch(pattern.as_slice(), patch.as_slice());
        for off in &v {
            eprintln!("Patch @ {off:#010X} [{from}] -> [{to}]");
        }
        Ok(!v.is_empty())
    }();
    res.unwrap_or(false)
}
