use super::*;

pub(super) fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), PackageError> {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let temp = TempFile::new(parent, path.file_name().unwrap_or_default())?;
    fs::write(temp.path(), bytes)?;
    #[cfg(windows)]
    if path.exists() {
        fs::remove_file(path)?;
    }
    fs::rename(temp.path(), path)?;
    Ok(())
}

pub(super) struct TempFile {
    path: PathBuf,
}
impl TempFile {
    pub(super) fn new(parent: &Path, name: &std::ffi::OsStr) -> Result<Self, PackageError> {
        for _ in 0..100 {
            let id = TEMP_ID.fetch_add(1, Ordering::Relaxed);
            let path = parent.join(format!(".{}.axiora-{id}.tmp", name.to_string_lossy()));
            match OpenOptions::new().write(true).create_new(true).open(&path) {
                Ok(_) => return Ok(Self { path }),
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error.into()),
            }
        }
        Err(PackageError::Invalid(
            "Could not allocate a temporary output file.".into(),
        ))
    }
    pub(super) fn path(&self) -> &Path {
        &self.path
    }
}
impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

pub(super) struct TempDir {
    path: PathBuf,
}
impl TempDir {
    #[cfg(test)]
    pub(super) fn new(prefix: &str) -> Result<Self, PackageError> {
        Self::new_in(prefix, &std::env::temp_dir())
    }

    pub(super) fn new_in(prefix: &str, base: &Path) -> Result<Self, PackageError> {
        for _ in 0..100 {
            let id = TEMP_ID.fetch_add(1, Ordering::Relaxed);
            let path = base.join(format!("{prefix}-{}-{id}", std::process::id()));
            match fs::create_dir(&path) {
                Ok(()) => {
                    #[cfg(unix)]
                    {
                        use std::os::unix::fs::PermissionsExt;
                        fs::set_permissions(&path, fs::Permissions::from_mode(0o700))?;
                    }
                    return Ok(Self { path });
                }
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error.into()),
            }
        }
        Err(PackageError::Invalid(
            "Could not allocate a private staging directory.".into(),
        ))
    }
    pub(super) fn path(&self) -> &Path {
        &self.path
    }
}
impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}
