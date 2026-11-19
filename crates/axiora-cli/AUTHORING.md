# Axiora content authoring (schema 0.1.0)

Axiora packages are authored as UTF-8 JSON files. `axiora` runs locally; it does not use an account, network service, or AI. Validation checks the content contract, not mathematical truth, licensing, or educational quality. A generated lesson is a draft with placeholders, not reviewed teaching material.

## Create a package

```sh
axiora init my-course --id example.course --title "My course"
axiora add unit my-course --id example.course.second --title "Second lesson" --concept example.course.concept
```

Both commands accept `--dry-run`. They refuse to overwrite existing destinations. `init` creates `manifest.json`, `concepts/core.json`, and `units/first.json`. `add unit` creates a draft exposition unit and adds it to the manifest and entry units. It requires an existing concept ID.

## Edit the JSON

Use a text editor or a JSON-aware tool. Keep valid JSON: strings use double quotes, literal newlines in strings must be escaped, and duplicate object keys are invalid. Keep the existing fields and follow the content contract for supported additions.

- In `concepts/core.json`, replace the concept title, definition, and assumptions with accurate content. The `id` is a stable identity; unit `concept_ids` and objective `concept_id` refer to it.
- In `units/first.json`, replace the title, objective description, source citation, locator, and rights placeholders. The `entry` field names the first key in `activities`; an exposition's `next` names its successor. Edit `content_md` and `summary_md` to teach and summarize the lesson. Text ending in `_md` contains Markdown inside a JSON string; escape backslashes used in LaTeX (for example, `"$\\mathbb{Z}$"`).
- In `manifest.json`, set the title, language, conventions, and truthful license and review information. The manifest lists concepts, units, entry units, capabilities, and an inventory of payload files. Keep IDs and references consistent. `files` stores each payload's raw byte count and SHA-256; do not include the manifest itself. Hashes detect changes and do not establish authenticity.

Source records must reflect actual provenance and rights. Do not claim review or a license without supporting evidence. Keep IDs stable when editing prose. Published package revisions are immutable: update package and unit versions as appropriate when revising content. Changes to answer keys, objectives, rubrics, or assessment meaning need a version and migration review; see the content contract.

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
