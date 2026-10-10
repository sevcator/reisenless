use std::fs::File;
use std::io::{self, Read};

const EOCD_MAGIC: u32 = 0x06054B50;
const APK_SIGNING_BLOCK_MAGIC: [u8; 16] = *b"APK Sig Block 42";
const SIGNATURE_SCHEME_V2_MAGIC: u32 = 0x7109871A;
const MAX_APK_SIZE: usize = 256 * 1024 * 1024;
const MAX_SIGNING_BLOCK_SIZE: usize = 16 * 1024 * 1024;
const MAX_CERTIFICATE_SIZE: usize = 1024 * 1024;

macro_rules! bad_apk {
    ($msg:literal) => {
        io::Error::new(io::ErrorKind::InvalidData, concat!("cert: ", $msg))
    };
}

fn le_u16(data: &[u8], offset: usize) -> io::Result<u16> {
    let end = offset
        .checked_add(2)
        .ok_or_else(|| bad_apk!("offset overflow"))?;
    let bytes: [u8; 2] = data
        .get(offset..end)
        .ok_or_else(|| bad_apk!("truncated integer"))?
        .try_into()
        .map_err(|_| bad_apk!("invalid integer"))?;
    Ok(u16::from_le_bytes(bytes))
}

fn le_u32(data: &[u8], offset: usize) -> io::Result<u32> {
    let end = offset
        .checked_add(4)
        .ok_or_else(|| bad_apk!("offset overflow"))?;
    let bytes: [u8; 4] = data
        .get(offset..end)
        .ok_or_else(|| bad_apk!("truncated integer"))?
        .try_into()
        .map_err(|_| bad_apk!("invalid integer"))?;
    Ok(u32::from_le_bytes(bytes))
}

fn le_u64(data: &[u8], offset: usize) -> io::Result<u64> {
    let end = offset
        .checked_add(8)
        .ok_or_else(|| bad_apk!("offset overflow"))?;
    let bytes: [u8; 8] = data
        .get(offset..end)
        .ok_or_else(|| bad_apk!("truncated integer"))?
        .try_into()
        .map_err(|_| bad_apk!("invalid integer"))?;
    Ok(u64::from_le_bytes(bytes))
}

fn length_prefixed<'a>(data: &'a [u8], offset: &mut usize, limit: usize) -> io::Result<&'a [u8]> {
    let length = le_u32(data, *offset)? as usize;
    if length > limit {
        return Err(bad_apk!("length-prefixed field too large"));
    }
    *offset = offset
        .checked_add(4)
        .ok_or_else(|| bad_apk!("offset overflow"))?;
    let end = offset
        .checked_add(length)
        .ok_or_else(|| bad_apk!("offset overflow"))?;
    let value = data
        .get(*offset..end)
        .ok_or_else(|| bad_apk!("truncated field"))?;
    *offset = end;
    Ok(value)
}

fn parse_v2_certificate(value: &[u8]) -> io::Result<Vec<u8>> {
    let mut value_offset = 0;
    let signers = length_prefixed(value, &mut value_offset, MAX_SIGNING_BLOCK_SIZE)?;
    if value_offset != value.len() {
        return Err(bad_apk!("trailing signer data"));
    }
    let mut signers_offset = 0;
    let signer = length_prefixed(signers, &mut signers_offset, MAX_SIGNING_BLOCK_SIZE)?;
    if signers_offset != signers.len() {
        return Err(bad_apk!("multiple signers are not allowed"));
    }

    let mut signer_offset = 0;
    let signed_data = length_prefixed(signer, &mut signer_offset, MAX_SIGNING_BLOCK_SIZE)?;
    let _signatures = length_prefixed(signer, &mut signer_offset, MAX_SIGNING_BLOCK_SIZE)?;
    let _public_key = length_prefixed(signer, &mut signer_offset, MAX_SIGNING_BLOCK_SIZE)?;
    if signer_offset != signer.len() {
        return Err(bad_apk!("trailing signer fields"));
    }

    let mut signed_offset = 0;
    let _digests = length_prefixed(signed_data, &mut signed_offset, MAX_SIGNING_BLOCK_SIZE)?;
    let certificates = length_prefixed(signed_data, &mut signed_offset, MAX_SIGNING_BLOCK_SIZE)?;
    let _attributes = length_prefixed(signed_data, &mut signed_offset, MAX_SIGNING_BLOCK_SIZE)?;

    let tail = &signed_data[signed_offset..];
    if !tail.is_empty() && tail != [0; 4] {
        return Err(bad_apk!("trailing signed data"));
    }
    let mut cert_offset = 0;
    let certificate = length_prefixed(certificates, &mut cert_offset, MAX_CERTIFICATE_SIZE)?;
    if certificate.is_empty() || cert_offset != certificates.len() {
        return Err(bad_apk!("invalid certificate chain"));
    }
    Ok(certificate.to_vec())
}

fn comment_version(comment: &[u8]) -> i32 {
    std::str::from_utf8(comment)
        .ok()
        .and_then(|text| {
            text.lines().find_map(|line| {
                line.strip_prefix("versionCode=")?
                    .trim()
                    .parse::<i32>()
                    .ok()
            })
        })
        .unwrap_or(0)
}

pub fn parse_certificate(apk: &[u8], version: i32) -> io::Result<Vec<u8>> {
    if apk.len() < 22 || apk.len() > MAX_APK_SIZE {
        return Err(bad_apk!("invalid APK format"));
    }
    let search_start = apk.len().saturating_sub(22 + u16::MAX as usize);
    let mut eocd = None;
    for offset in (search_start..=apk.len() - 22).rev() {
        if le_u32(apk, offset)? != EOCD_MAGIC {
            continue;
        }
        let comment_length = le_u16(apk, offset + 20)? as usize;
        if offset.checked_add(22 + comment_length) == Some(apk.len()) {
            eocd = Some((offset, comment_length));
            break;
        }
    }
    let (eocd, comment_length) = eocd.ok_or_else(|| bad_apk!("invalid APK format"))?;
    let central_dir = le_u32(apk, eocd + 16)? as usize;
    if central_dir < 24 || central_dir > eocd {
        return Err(bad_apk!("invalid central directory offset"));
    }

    if version >= 0 {
        let end = eocd
            .checked_add(22 + comment_length)
            .ok_or_else(|| bad_apk!("offset overflow"))?;
        let comment = apk
            .get(eocd + 22..end)
            .ok_or_else(|| bad_apk!("truncated APK comment"))?;
        if version > comment_version(comment) {
            return Err(bad_apk!("APK version too low"));
        }
    }

    let footer = central_dir - 24;
    let block_size =
        usize::try_from(le_u64(apk, footer)?).map_err(|_| bad_apk!("signing block too large"))?;
    if apk.get(footer + 8..central_dir) != Some(APK_SIGNING_BLOCK_MAGIC.as_slice()) {
        return Err(bad_apk!("invalid signing block magic"));
    }
    let total_size = block_size
        .checked_add(8)
        .ok_or_else(|| bad_apk!("size overflow"))?;
    if block_size < 24 || total_size > MAX_SIGNING_BLOCK_SIZE || total_size > central_dir {
        return Err(bad_apk!("invalid signing block size"));
    }
    let block_start = central_dir - total_size;
    if usize::try_from(le_u64(apk, block_start)?).ok() != Some(block_size) {
        return Err(bad_apk!("mismatched signing block size"));
    }

    let mut offset = block_start + 8;
    let mut certificate = None;
    while offset < footer {
        if footer - offset < 8 {
            return Err(bad_apk!("truncated signing pair size"));
        }
        let pair_size = usize::try_from(le_u64(apk, offset)?)
            .map_err(|_| bad_apk!("signing pair too large"))?;
        offset = offset
            .checked_add(8)
            .ok_or_else(|| bad_apk!("offset overflow"))?;
        if pair_size < 4 || pair_size > footer - offset {
            return Err(bad_apk!("invalid signing pair"));
        }
        let id = le_u32(apk, offset)?;
        let value_start = offset + 4;
        let pair_end = offset
            .checked_add(pair_size)
            .ok_or_else(|| bad_apk!("offset overflow"))?;
        if id == SIGNATURE_SCHEME_V2_MAGIC {
            if certificate.is_some() {
                return Err(bad_apk!("multiple v2 blocks are not allowed"));
            }
            certificate = Some(parse_v2_certificate(&apk[value_start..pair_end])?);
        }
        offset = pair_end;
    }
    certificate.ok_or_else(|| bad_apk!("cannot find certificate"))
}

pub fn read_certificate(apk: &mut File, version: i32) -> io::Result<Vec<u8>> {
    let length = usize::try_from(apk.metadata()?.len()).map_err(|_| bad_apk!("APK too large"))?;
    if length > MAX_APK_SIZE {
        return Err(bad_apk!("APK too large"));
    }
    let mut data = Vec::with_capacity(length);

    apk.take((length + 1) as u64).read_to_end(&mut data)?;
    if data.len() != length {
        return Err(bad_apk!("APK length changed while reading"));
    }
    parse_certificate(&data, version)
}
