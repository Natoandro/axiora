# Axiora content authoring (schema 0.1.0)

Axiora is an offline mathematics learning environment. Authors prepare concepts and lessons as course packages for learners to study in the Axiora app. This CLI helps create a draft package, edit its content, check its structure, and build or compare package archives. It runs locally without an account, network service, or AI.

Packages are authored as UTF-8 JSON files. Validation checks the content contract, not mathematical truth, licensing, or educational quality. A generated lesson is a draft with placeholders, not reviewed teaching material. The CLI does not provide a learner-facing preview or author-defined lesson tests; review the mathematics, teaching, rights, and rendered appearance separately.

## Create a package

```sh
axiora init my-course --id example.course --title "My course"
axiora add unit my-course --id example.course.second --title "Second lesson" --concept example.course.concept
```

Both commands accept `--dry-run`. They refuse to overwrite existing destinations. `init` creates `manifest.json`, `concepts/core.json`, and `units/first.json`. `add unit` creates a draft exposition unit and adds it to the manifest and entry units. It requires an existing concept ID.

### Files in a starter package

- `manifest.json` identifies the package, lists its concepts and units, declares required capabilities, and records each payload's size and SHA-256 hash.
- `concepts/core.json` contains concept definitions and assumptions.
- `units/first.json` contains objectives, source records, and the lesson activity flow.

Edit the concept and unit files for your course. Edit the manifest when package metadata, unit listings, entry units, review status, or licensing information changes. After editing payload files, run `axiora refresh` to update their inventory records.

## Edit the JSON

Use a text editor or a JSON-aware tool. Keep valid JSON: strings use double quotes, literal newlines in strings must be escaped, and duplicate object keys are invalid. Keep the existing fields and follow the content contract for supported additions.

- In `concepts/core.json`, replace the concept title, definition, and assumptions with accurate content. The `id` is a stable identity; unit `concept_ids` and objective `concept_id` refer to it.
- In `units/first.json`, replace the title, objective description, source citation, locator, and rights placeholders. The `entry` field names the first key in `activities`; an exposition's `next` names its successor. Edit `content_md` and `summary_md` to teach and summarize the lesson. Text ending in `_md` contains Markdown inside a JSON string; escape backslashes used in LaTeX (for example, `"$\\mathbb{Z}$"`).
- In `manifest.json`, set the title, language, conventions, and truthful license and review information. The manifest lists concepts, units, entry units, capabilities, and an inventory of payload files. Keep IDs and references consistent. `files` stores each payload's raw byte count and SHA-256; do not include the manifest itself. Hashes detect changes and do not establish authenticity.

Source records must reflect actual provenance and rights. Do not claim review or a license without supporting evidence. Keep IDs stable when editing prose. Published package revisions are immutable: update package and unit versions as appropriate when revising content. Changes to answer keys, objectives, rubrics, or assessment meaning need a version and migration review; see the content contract.

## Mathematical notation

Use LaTeX math expressions in Markdown fields, with `$...$` for inline math and `$$...$$` for display math where the lesson renderer supports it. Because the Markdown is stored inside JSON strings, write each LaTeX backslash as `\\`. For example, the JSON value for inline integers is `"content_md": "The integers are $\\mathbb{Z}$."`.

Axiora's Rust math engine, Texlet, currently supports a small expression subset: basic letters, digits and operators, parentheses and brackets, `\in`, and single-letter blackboard-bold sets such as `\mathbb{Z}`. Broader syntax such as fractions, roots, scripts, Greek letters, and matrices is on the notation roadmap; do not assume arbitrary LaTeX or TeX packages will work. Actual display also depends on the renderer and its font. The CLI validates package structure but does not render or preview formulas. The [Texlet notation roadmap](https://github.com/Natoandro/axiora/blob/main/crates/texlet/ROADMAP.md) lists supported and planned syntax. Use the app or a suitable renderer to visually review formulas.

For example, this JSON field stores an inline formula using the currently supported set notation:

```json
"content_md": "Every integer is in $\\mathbb{Z}$."
```

Then update the inventory, validate, and build the archive:

```sh
axiora refresh my-course
axiora validate my-course
axiora pack my-course --output my-course.axp
```

The CLI checks the JSON shape, identifiers and references, activity flow, declared capabilities, payload inventory, sizes, and hashes. A successful validation means the package satisfies those machine-checkable rules. It does not confirm that formulas render correctly, mathematics is true, sources may be redistributed, or lessons teach well. Those require appropriate renderer checks and human review.

The starter unit only illustrates exposition and an end node. Choice, proof, reflection, feedback, evaluation, and branching have additional required fields and capabilities. See `docs/markdown/content.md` and `examples/prime-and-maximal/` in the Axiora repository for the complete contract and examples; `axiora schema` prints a machine-readable discovery summary, not a full JSON Schema.

## Check and package

```sh
axiora refresh my-course --dry-run
axiora refresh my-course
axiora validate my-course --format json
axiora pack my-course --output my-course.axp
axiora inspect my-course.axp
```

After editing payloads, `refresh` recalculates their sizes and hashes. It requires all other validation checks to pass and leaves the manifest unchanged on failure. `validate` emits diagnostics with codes, file paths and field paths; fix reported issues before packaging. The archive and adjacent report must be written outside the content directory. Existing output is not replaced without `--overwrite`.

Use `axiora diff old-directory new-directory --format json` to compare validated revisions; archives are also accepted. The comparison is structural and does not decide whether a mathematical or educational meaning changed. The current toolkit does not provide a learner-facing preview or author-defined lesson tests.

## Troubleshooting

- **JSON parse error:** check quotation marks, commas, braces, and escaped characters. A backslash inside a JSON string must be doubled; literal line breaks inside strings must also be escaped.
- **Unknown concept or unit ID:** IDs are references. Make sure each referenced concept or unit exists and that spelling and capitalization match exactly.
- **Activity target or entry error:** the unit's `entry` must name an activity, and each transition target must name an existing activity. Check for misspelled IDs and disconnected activities.
- **Inventory size or hash mismatch:** after editing a concept or unit payload, run `axiora refresh my-course`, then validate again. Refresh updates payload inventory records; it does not fix other validation errors.
- **Unsupported capability or notation:** make sure the package declares the capabilities its activities require. Texlet supports only a subset of LaTeX; consult the notation roadmap and simplify or rewrite unsupported expressions.
- **Refresh fails:** fix validation errors first. Refresh only updates inventory after the rest of the package is valid, and supports `--dry-run` to preview its changes.
