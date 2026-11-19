use super::*;

pub(super) fn parse_directory(
    bytes: &[u8],
    require_utf8_flag: bool,
) -> Result<Vec<ZipEntry>, PackageError> {
    let lower = bytes.len().saturating_sub(65_557);
    let end_record = (lower..=bytes.len().saturating_sub(22))
        .rev()
        .find(|position| read_u32(bytes, *position).ok() == Some(0x06054b50))
        .ok_or_else(|| PackageError::Invalid("ZIP end record is missing.".into()))?;
    let disk = read_u16(bytes, end_record + 4)?;
    let central_disk = read_u16(bytes, end_record + 6)?;
    let disk_count = read_u16(bytes, end_record + 8)?;
    let count = read_u16(bytes, end_record + 10)?;
    let size = read_u32(bytes, end_record + 12)?;
    let offset = read_u32(bytes, end_record + 16)?;
    let comment_length = read_u16(bytes, end_record + 20)? as usize;
    if disk != 0
        || central_disk != 0
        || disk_count != count
        || count == u16::MAX
        || size == u32::MAX
        || offset == u32::MAX
    {
        return Err(PackageError::Invalid(
            "Multi-disk and ZIP64 archives are unsupported.".into(),
        ));
    }
    if end_record + 22 + comment_length != bytes.len()
        || u64::from(offset) + u64::from(size) > end_record as u64
    {
        return Err(PackageError::Invalid(
            "Invalid ZIP central directory bounds.".into(),
        ));
    }
    let mut position = offset as usize;
    let mut result = Vec::with_capacity(count as usize);
    for _ in 0..count {
        if read_u32(bytes, position)? != 0x02014b50 {
            return Err(PackageError::Invalid(
                "Invalid ZIP central directory entry.".into(),
            ));
        }
        let made_by = read_u16(bytes, position + 4)?;
        let flags = read_u16(bytes, position + 8)?;
        let method = read_u16(bytes, position + 10)?;
        let crc = read_u32(bytes, position + 16)?;
        let compressed_size = read_u32(bytes, position + 20)?;
        let uncompressed_size = read_u32(bytes, position + 24)?;
        let name_len = read_u16(bytes, position + 28)? as usize;
        let extra_len = read_u16(bytes, position + 30)? as usize;
        let comment_len = read_u16(bytes, position + 32)? as usize;
        let disk_start = read_u16(bytes, position + 34)?;
        let external_attributes = read_u32(bytes, position + 38)?;
        let local_offset = read_u32(bytes, position + 42)?;
        let end = position
            .checked_add(46 + name_len + extra_len + comment_len)
            .ok_or_else(|| {
                PackageError::Invalid("Truncated ZIP central directory entry.".into())
            })?;
        if end > bytes.len()
            || disk_start != 0
            || flags & 1 != 0
            || (require_utf8_flag && flags & (1 << 11) == 0)
            || compressed_size == u32::MAX
            || uncompressed_size == u32::MAX
        {
            return Err(PackageError::Invalid(
                "Encrypted, multi-disk, non-UTF-8, or ZIP64 entries are unsupported.".into(),
            ));
        }
        let name_bytes = &bytes[position + 46..position + 46 + name_len];
        let name = std::str::from_utf8(name_bytes)
            .map_err(|_| PackageError::Invalid("ZIP entry names must be valid UTF-8.".into()))?
            .to_owned();
        if flags & (1 << 11) == 0 && !name.is_ascii() {
            return Err(PackageError::Invalid(
                "ZIP entry names must be explicitly UTF-8.".into(),
            ));
        }
        let local = local_offset as usize;
        if local as u64 >= u64::from(offset) || read_u32(bytes, local)? != 0x04034b50 {
            return Err(PackageError::Invalid(format!(
                "Invalid ZIP local file header: {name}"
            )));
        }
        let local_flags = read_u16(bytes, local + 6)?;
        let local_method = read_u16(bytes, local + 8)?;
        let local_crc = read_u32(bytes, local + 14)?;
        let local_compressed = read_u32(bytes, local + 18)?;
        let local_uncompressed = read_u32(bytes, local + 22)?;
        let local_name_len = read_u16(bytes, local + 26)? as usize;
        let local_extra_len = read_u16(bytes, local + 28)? as usize;
        let data_start = local
            .checked_add(30 + local_name_len + local_extra_len)
            .ok_or_else(|| PackageError::Invalid("Invalid ZIP local file header.".into()))?;
        let data_end = data_start
            .checked_add(compressed_size as usize)
            .ok_or_else(|| PackageError::Invalid("Invalid ZIP local file header.".into()))?;
        if local_flags != flags
            || local_method != method
            || data_end > offset as usize
            || local + 30 + local_name_len > bytes.len()
            || std::str::from_utf8(&bytes[local + 30..local + 30 + local_name_len]).ok()
                != Some(name.as_str())
        {
            return Err(PackageError::Invalid(format!(
                "ZIP local header does not match central directory: {name}"
            )));
        }
        if flags & 8 == 0
            && (local_crc != crc
                || local_compressed != compressed_size
                || local_uncompressed != uncompressed_size)
        {
            return Err(PackageError::Invalid(format!(
                "ZIP local sizes or CRC do not match central directory: {name}"
            )));
        }
        let central_position = position;
        position = end;
        result.push(ZipEntry {
            name,
            method,
            compressed_size,
            uncompressed_size,
            offset: local_offset,
            made_by,
            external_attributes,
            central_position,
        });
    }
    if position as u64 != u64::from(offset) + u64::from(size) {
        return Err(PackageError::Invalid(
            "Unexpected data in ZIP central directory.".into(),
        ));
    }
    Ok(result)
}

pub(super) fn mark_utf8_flags(bytes: &mut [u8]) -> Result<(), PackageError> {
    let entries = parse_directory(bytes, false)?;
    for entry in entries {
        let local = entry.offset as usize;
        let local_flags = read_u16(bytes, local + 6)? | (1 << 11);
        let central_flags = read_u16(bytes, entry.central_position + 8)? | (1 << 11);
        bytes[local + 6..local + 8].copy_from_slice(&local_flags.to_le_bytes());
        bytes[entry.central_position + 8..entry.central_position + 10]
            .copy_from_slice(&central_flags.to_le_bytes());
    }
    parse_directory(bytes, true)?;
    Ok(())
}

pub(super) fn validate_archive_path(name: &str) -> Result<(), PackageError> {
    if name.is_empty()
        || name.starts_with('/')
        || name.contains('\\')
        || name.contains('\0')
        || name
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
    {
        return Err(PackageError::Invalid(format!(
            "Unsafe archive path: {name}"
        )));
    }
    Ok(())
}

pub(super) fn read_u16(bytes: &[u8], position: usize) -> Result<u16, PackageError> {
    let bytes = bytes
        .get(position..position + 2)
        .ok_or_else(|| PackageError::Invalid("Truncated ZIP metadata.".into()))?;
    Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
}

pub(super) fn read_u32(bytes: &[u8], position: usize) -> Result<u32, PackageError> {
    let bytes = bytes
        .get(position..position + 4)
        .ok_or_else(|| PackageError::Invalid("Truncated ZIP metadata.".into()))?;
    Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}
