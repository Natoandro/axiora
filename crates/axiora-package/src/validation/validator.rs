use super::*;

#[derive(Default)]
pub(super) struct Validator {
    pub(super) diagnostics: Vec<Diagnostic>,
}

impl Validator {
    pub(super) fn error(&mut self, code: &str, file: &str, path: &str, message: impl Into<String>) {
        self.error_with_suggestion(code, file, path, message, None);
    }

    pub(super) fn error_with_suggestion(
        &mut self,
        code: &str,
        file: &str,
        path: &str,
        message: impl Into<String>,
        suggestion: Option<String>,
    ) {
        self.diagnostics.push(Diagnostic {
            code: code.into(),
            severity: "error".into(),
            file: file.into(),
            path: path.into(),
            message: message.into(),
            suggestion,
        });
    }

    pub(super) fn warning(
        &mut self,
        code: &str,
        file: &str,
        path: &str,
        message: impl Into<String>,
    ) {
        self.diagnostics.push(Diagnostic {
            code: code.into(),
            severity: "warning".into(),
            file: file.into(),
            path: path.into(),
            message: message.into(),
            suggestion: None,
        });
    }

    pub(super) fn object(
        &mut self,
        value: Option<&Value>,
        file: &str,
        path: &str,
        required: &[&str],
    ) -> bool {
        let Some(Value::Object(object)) = value else {
            self.error("AXC110", file, path, "Expected an object.");
            return false;
        };
        for key in required {
            if !object.contains_key(*key) {
                self.error(
                    "AXC110",
                    file,
                    &format!("{path}.{key}"),
                    format!("Required field '{key}' is missing."),
                );
            }
        }
        for key in object.keys() {
            if !required.contains(&key.as_str()) {
                self.error(
                    "AXC110",
                    file,
                    &format!("{path}.{key}"),
                    format!("Unknown field '{key}'."),
                );
            }
        }
        true
    }

    pub(super) fn object_optional(
        &mut self,
        value: Option<&Value>,
        file: &str,
        path: &str,
        required: &[&str],
        optional: &[&str],
    ) -> bool {
        let Some(Value::Object(object)) = value else {
            self.error("AXC110", file, path, "Expected an object.");
            return false;
        };
        for key in required {
            if !object.contains_key(*key) {
                self.error(
                    "AXC110",
                    file,
                    &format!("{path}.{key}"),
                    format!("Required field '{key}' is missing."),
                );
            }
        }
        for key in object.keys() {
            if !required.contains(&key.as_str()) && !optional.contains(&key.as_str()) {
                self.error(
                    "AXC110",
                    file,
                    &format!("{path}.{key}"),
                    format!("Unknown field '{key}'."),
                );
            }
        }
        true
    }

    pub(super) fn string(
        &mut self,
        value: Option<&Value>,
        file: &str,
        path: &str,
        max: usize,
    ) -> Option<String> {
        match value {
            Some(Value::String(value))
                if !value.trim().is_empty() && value.chars().count() <= max =>
            {
                Some(value.clone())
            }
            _ => {
                self.error(
                    "AXC110",
                    file,
                    path,
                    format!("Expected a nonempty string of at most {max} characters."),
                );
                None
            }
        }
    }

    pub(super) fn id(&mut self, value: Option<&Value>, file: &str, path: &str) -> Option<String> {
        let value = self.string(value, file, path, 120)?;
        if valid_id(&value) {
            Some(value)
        } else {
            self.error(
                "AXC201",
                file,
                path,
                format!("Invalid stable ID '{value}'."),
            );
            None
        }
    }

    pub(super) fn revision(
        &mut self,
        value: Option<&Value>,
        file: &str,
        path: &str,
    ) -> Option<String> {
        let value = self.string(value, file, path, 32)?;
        if valid_revision(&value) {
            Some(value)
        } else {
            self.error(
                "AXC101",
                file,
                path,
                "Expected a three-part numeric version.",
            );
            None
        }
    }

    pub(super) fn string_array(
        &mut self,
        value: Option<&Value>,
        file: &str,
        path: &str,
        nonempty: bool,
    ) -> Vec<String> {
        let Some(Value::Array(values)) = value else {
            self.error(
                "AXC110",
                file,
                path,
                format!(
                    "Expected {}array of strings.",
                    if nonempty { "a nonempty " } else { "an " }
                ),
            );
            return vec![];
        };
        if nonempty && values.is_empty() {
            self.error(
                "AXC110",
                file,
                path,
                "Expected a nonempty array of strings.",
            );
        }
        let mut strings = Vec::new();
        for (index, value) in values.iter().enumerate() {
            if let Some(value) = self.string(Some(value), file, &format!("{path}[{index}]"), 120) {
                strings.push(value);
            }
        }
        if strings.iter().collect::<HashSet<_>>().len() != strings.len() {
            self.error("AXC110", file, path, "Array values must be unique.");
        }
        strings
    }

    pub(super) fn read_json(&mut self, root: &Path, relative: &str, limit: u64) -> Option<Value> {
        if !safe_relative_path(relative) {
            self.error("AXC103", relative, "$", "Unsafe relative path.");
            return None;
        }
        let path = root.join(relative);
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => metadata,
            _ => {
                self.error(
                    "AXC103",
                    relative,
                    "$",
                    "File is missing or is not a regular file.",
                );
                return None;
            }
        };
        if metadata.len() > limit {
            self.error(
                "AXC103",
                relative,
                "$",
                format!("File exceeds the {limit}-byte limit."),
            );
            return None;
        }
        let mut bytes = Vec::with_capacity(metadata.len() as usize);
        let file = match fs::File::open(&path) {
            Ok(file) => file,
            Err(error) => {
                self.error(
                    "AXC103",
                    relative,
                    "$",
                    format!("Cannot read file: {error}"),
                );
                return None;
            }
        };
        if file.take(limit + 1).read_to_end(&mut bytes).is_err() || bytes.len() as u64 > limit {
            self.error(
                "AXC103",
                relative,
                "$",
                format!("File exceeds the {limit}-byte limit."),
            );
            return None;
        }
        match parse_json_strict(&bytes, relative) {
            Ok(value) => Some(value),
            Err(diagnostic) => {
                self.diagnostics.push(diagnostic);
                None
            }
        }
    }
}
