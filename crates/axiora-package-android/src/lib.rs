use axiora_package::{PackageDescriptor, load_archive_in, write_loaded_package};
use serde_json::{Value, json};
use std::ffi::{CStr, c_char};
use std::mem;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;

#[repr(C)]
pub struct AxioraBuffer {
    pub data: *mut u8,
    pub len: usize,
    pub capacity: usize,
}

fn path_from_c(path: *const c_char) -> Result<PathBuf, String> {
    if path.is_null() {
        return Err("Archive path is null.".into());
    }
    // SAFETY: the caller provides a NUL-terminated string for the duration of this call.
    let path = unsafe { CStr::from_ptr(path) };
    let path = path
        .to_str()
        .map_err(|_| "Archive path is not UTF-8.".to_owned())?;
    Ok(PathBuf::from(path))
}

fn descriptor_json(descriptor: PackageDescriptor) -> Value {
    json!({
        "package_id": descriptor.package_id,
        "version": descriptor.version,
        "manifest_sha256": descriptor.manifest_sha256,
        "archive_sha256": descriptor.archive_sha256,
        "payload_count": descriptor.payload_count,
        "uncompressed_bytes": descriptor.uncompressed_bytes,
    })
}

fn into_buffer(value: Value) -> AxioraBuffer {
    let mut bytes = serde_json::to_vec(&value).unwrap_or_else(|_| {
        b"{\"ok\":false,\"error\":\"Could not serialize native result.\"}".to_vec()
    });
    let buffer = AxioraBuffer {
        data: bytes.as_mut_ptr(),
        len: bytes.len(),
        capacity: bytes.capacity(),
    };
    mem::forget(bytes);
    buffer
}

fn response(operation: impl FnOnce() -> Result<Value, String>) -> AxioraBuffer {
    let value = match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"ok": false, "error": error}),
        Err(_) => json!({"ok": false, "error": "Rust package operation failed."}),
    };
    into_buffer(value)
}

#[unsafe(no_mangle)]
pub extern "C" fn axiora_package_inspect_json(
    path: *const c_char,
    scratch: *const c_char,
) -> AxioraBuffer {
    response(|| {
        let path = path_from_c(path)?;
        let scratch = path_from_c(scratch)?;
        let package = load_archive_in(&path, &scratch).map_err(|error| error.to_string())?;
        Ok(json!({
            "ok": true,
            "descriptor": descriptor_json(package.descriptor),
        }))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn axiora_package_load_to_directory_json(
    path: *const c_char,
    staging: *const c_char,
) -> AxioraBuffer {
    response(|| {
        let path = path_from_c(path)?;
        let staging = path_from_c(staging)?;
        let package = load_archive_in(&path, &staging).map_err(|error| error.to_string())?;
        write_loaded_package(&package, &staging).map_err(|error| error.to_string())?;
        Ok(json!({
            "ok": true,
            "descriptor": descriptor_json(package.descriptor),
        }))
    })
}

/// Release a buffer returned by one of this crate's exported operations.
#[unsafe(no_mangle)]
pub extern "C" fn axiora_package_free_buffer(buffer: AxioraBuffer) {
    if !buffer.data.is_null() {
        // SAFETY: these values are returned together by `into_buffer` and freed once by the C shim.
        unsafe {
            drop(Vec::from_raw_parts(
                buffer.data,
                buffer.len,
                buffer.capacity,
            ));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    fn package_path() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../axiora-package/tests/fixtures/kotlin-reference/prime-and-maximal.axp")
    }

    fn decode(buffer: AxioraBuffer) -> Value {
        // SAFETY: the returned buffer remains owned until the matching free call below.
        let bytes = unsafe { std::slice::from_raw_parts(buffer.data, buffer.len) };
        let value = serde_json::from_slice(bytes).unwrap();
        axiora_package_free_buffer(buffer);
        value
    }

    #[test]
    fn inspect_ffi_returns_descriptor_and_owned_buffer() {
        let path = CString::new(package_path().to_string_lossy().as_bytes()).unwrap();
        let scratch = CString::new(std::env::temp_dir().to_string_lossy().as_bytes()).unwrap();
        let result = decode(axiora_package_inspect_json(path.as_ptr(), scratch.as_ptr()));
        assert_eq!(result["ok"], true);
        assert_eq!(result["descriptor"]["package_id"], "axiora.ca.first-slice");
        assert_eq!(result["descriptor"]["payload_count"], 4);
    }

    #[test]
    fn load_ffi_writes_validated_files_to_caller_owned_staging() {
        let path = CString::new(package_path().to_string_lossy().as_bytes()).unwrap();
        let staging =
            std::env::temp_dir().join(format!("axiora-native-stage-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&staging);
        std::fs::create_dir(&staging).unwrap();
        let staging_c = CString::new(staging.to_string_lossy().as_bytes()).unwrap();
        let result = decode(axiora_package_load_to_directory_json(
            path.as_ptr(),
            staging_c.as_ptr(),
        ));
        assert_eq!(result["ok"], true);
        assert_eq!(result["descriptor"]["payload_count"], 4);
        assert!(staging.join("manifest.json").is_file());
        assert!(staging.join("units/proof-and-fresh-check.json").is_file());
        std::fs::remove_dir_all(staging).unwrap();
    }
}
