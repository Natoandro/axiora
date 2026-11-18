# Content contract

## Contract status

This is the proposed **Axiora content schema 0.1.0**. It is a design contract with a JSON example and an illustrative fixture. A strict Kotlin parser and shared validator are implemented for the compiler and package reader; the contract is checked in code rather than a standalone JSON Schema. This work completes milestone M1.

The example uses runtime JSON directly. A high-level YAML authoring language is deferred until several manually authored units reveal what abstraction actually helps. This reduces the risk of designing a language before learning what teaching needs to express.

## Proposed course descriptor for the next schema

The package structure is **Course → Section → Unit → Activity**. Learners open a course in the Library and choose a unit from its outline. A course orders units and recommends starting points. A unit owns objectives and a resumable activity graph. Activities are local nodes within that graph. A **section** groups consecutive units in the course outline. Every course has at least one section. A single untitled section renders as a plain unit list. Sections have no identity or learner state.

A package delivers one course descriptor alongside its unit and concept documents in the next schema. The package retains its immutable ID, version, and manifest hash. The course gains a stable namespaced ID, independent of the package ID, so the Library can recognize the same course across package revisions. A course descriptor is pinned by the package revision that contains it and needs no independent version in this format.

The manifest refers to the course descriptor by path and includes it in the file inventory. The descriptor owns the course title, outline order, and suggested entry units. The manifest unit inventory resolves those references to exact unit versions and paths. Language, license, review claim, capabilities, and conventions remain package metadata. The package title and entry unit fields move into the descriptor in the new schema.

Illustrative descriptor using the existing fixture's unit IDs:

```json
{
  "id": "axiora.ca.prime-and-maximal-ideals",
  "title": "Prime and maximal ideals",
  "sections": [
    {
      "title": null,
      "unit_ids": [
        "axiora.ca.ideal-prerequisites",
        "axiora.ca.prime-vs-maximal",
        "axiora.ca.maximal-implies-prime-proof"
      ]
    }
  ],
  "entry_units": ["axiora.ca.ideal-prerequisites"]
}
```

The descriptor has a nonempty ordered `sections` array. Each section contains exactly `title` and a nonempty ordered `unit_ids` array. The title is either a nonempty string or, for a course with exactly one section, `null`. Sections cannot nest. Every unit in the manifest appears exactly once across the sections. Entry units are nonempty and unique, and each references a unit in the outline. Entry points are recommendations rather than prerequisites. Editing section titles or moving units in the outline does not change unit or activity identity. Authoring or importing multiple courses from one archive is deferred.

A reader that supports both formats can present an existing schema 0.1 package as an implicit course, using the package ID as the legacy course identity, the manifest title as its title, the manifest unit order as one untitled section, and the manifest entry points as the course entry points. The old archive stays byte-for-byte intact. To continue an existing course's Library identity when an explicit descriptor first ships, the publisher should use the original package ID as the explicit course ID. A different ID gives the course a separate Library entry. Automatic merging across package IDs is deferred. Existing sessions, drafts, and attempts keep their exact package, unit, and activity references. A reader that only supports 0.1 rejects the new schema version.

## Names and identity

Use stable namespaced ASCII IDs, for example `axiora.ca.prime-vs-maximal`. IDs match `[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*` and are at most 120 characters. Renaming explanatory prose does not change an ID.

Each package and unit has a three-part numeric version such as `0.1.0`; prerelease syntax and version ranges are not supported initially. A unit ID identifies an educational contract; its version identifies one revision. Content hashes identify exact bytes. These are different responsibilities.

Published revisions are immutable. The same package ID and version with a different manifest hash is a conflict, not an update. An identical import is idempotent. An update can change the installed default for new sessions; it cannot silently replace the revision pinned to an active session.

## Package layout

`.axp` is a ZIP archive with UTF-8 JSON files, relative paths, and one root manifest.

```text
manifest.json
concepts/core.json
units/prime-vs-maximal.json
```

The [example directory](../../examples/prime-and-maximal/README.md) is the unpacked source of that layout. Its README is repository documentation and is not part of the archive inventory.

### Manifest fields

All fields in the following table are required. Unknown fields are rejected in schema 0.1; future extensions need an explicit schema change. JSON duplicate object keys must be rejected before deserialization.

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | version string | Exact supported content contract |
| `package_id` | stable ID | Identity across package updates |
| `version` | version string | Immutable package revision |
| `title` | nonempty string | Human-readable name |
| `language` | string | Content language tag, initially `en` |
| `license` | string | Distribution declaration; `UNLICENSED` means no distribution grant |
| `requires_capabilities` | unique string array | Features the reader must support |
| `conventions` | nonempty string | Mathematical assumptions applying to all units |
| `concept_files` | unique path array | Concept documents in this package |
| `units` | nonempty object array | Each contains `id`, `version`, and `path` |
| `entry_units` | nonempty ID array | Suggested start points, referencing manifest units |
| `review` | object | `status` and `note`, both strings |
| `files` | nonempty object array | Each contains `path`, `bytes`, and lowercase `sha256` |

The payload inventory lists every file except `manifest.json`; the manifest cannot hash itself. No undeclared archive entries are allowed. The manifest digest stored by the importer is calculated separately over its raw bytes. Each declared concept or unit path must appear in the payload inventory. Payload paths cannot be shared between two unit records.

SHA-256 values detect accidental or malicious byte changes relative to the manifest. They do not authenticate the author, publisher, or review claims.

Review status is one of `draft`, `mathematically_reviewed`, or `pilot_reviewed`. The latter two must include reviewer identity, date, scope, and evidence in the note or a future structured review record. Until publisher authentication exists, the app presents these as author claims. The fixture remains `draft`.

### Compatibility and limits

The 0.1 reader accepts only schema `0.1.0`. Later readers may explicitly support multiple versions; do not assume minor versions are readable. Required capabilities are an allowlist; an unknown required capability rejects import.

Initial required capabilities are `activity.exposition.v1`, `activity.choice.v1`, `activity.proof.v1`, `activity.reflection.v1`, `activity.end.v1`, `evaluator.exact-choice.v1`, and `evaluator.self-review.v1`. These version the implemented semantics independently of a package's own version.

Initial import limits are conservative policies, to be revised against representative courses:

- Archive size at most 20 MiB; total uncompressed payload at most 50 MiB.
- At most 500 files, each JSON file at most 2 MiB; manifest at most 256 KiB.
- At most 100 units and 500 activities per unit; JSON nesting depth at most 32.
- A Markdown field at most 100,000 Unicode characters; a title at most 200; an ID at most 120.
- UTF-8 JSON only, with bounded string and collection lengths and no duplicate object keys.
- ZIP entries must be regular files. Reject symlinks, encrypted entries, absolute paths, backslashes, empty path segments, `.` or `..` segments, NULs, and case-insensitive path collisions.
- Stop streaming extraction as soon as cumulative actual bytes exceed limits, regardless of declared archive sizes. Verify each declared byte count after extraction.

The 0.1 payload is text only. Media, fonts, HTML, executable code, custom evaluators, external embeds, and network-dependent assets are unsupported. Broader asset formats require their own size, parsing, and rendering policies.

## Concepts and prerequisites

A concept document has exactly `schema_version` and `concepts`. Each concept contains `id`, `title`, `definition_md`, `assumptions`, and `relations`. A relation contains `type` and `target`; type is `prerequisite`, `implies`, `equivalent`, or `generalizes`.

`prerequisite` points from the dependent concept to the concept it requires. `implies` points from premise to consequence. `generalizes` points from the more general concept to the narrower one. `equivalent` is semantically symmetric, though one record is enough to declare it. Only prerequisite edges must form a directed acyclic graph. Equivalences naturally permit cycles and must not be confused with teaching order.

Version 0.1 concept relation targets must be present in the same package. External prerequisite IDs may appear in a unit's `requires_concepts`, where they are advisory learning dependencies. They do not authorize missing executable or rendering dependencies. The reader may browse a unit with an unmet learning prerequisite but must show the gap.

Do not merge concepts solely by matching titles. Different ring conventions or theorem hypotheses can require distinct IDs or explicit assumptions. Version 0.1 prevents importing conflicting definitions under the same ID into one active course view; it does not attempt universal ontology reconciliation.

## Learning unit fields

All unit fields below are required. Units are independent compilation artifacts; collections are supplied by manifest entry points initially. Schema 0.1 has no separate course document. See the proposed descriptor above for the next schema.

| Field | Meaning |
| --- | --- |
| `schema_version`, `id`, `version`, `title` | Contract and identity |
| `concept_ids` | Nonempty unique list of locally defined concepts taught here |
| `requires_concepts` | Unique list of assumed prior concepts; local or external |
| `objectives` | Nonempty list of `{id, concept_id, skill, description}` |
| `sources` | Nonempty list of `{id, kind, citation, locator, rights}` |
| `entry` | ID of the first activity |
| `activities` | Nonempty mapping from local activity ID to activity |

Objective, source, and activity IDs are local stable IDs using the same syntax as global IDs. Objective `concept_id` must be in `concept_ids`. Skill keys are defined in [learning and assessment](pedagogy.md). `sources.kind` is `original` or `reference`; rights are a declaration, not an automated legal finding. Every activity must include a nonempty `source_ids` list pointing to local sources.

## Activity vocabulary

The first implementation supports five types. Richer constructed responses, proof-step ordering, and parameterized problems are deferred.

| Type | Required fields beyond `type` and `source_ids` | Behavior |
| --- | --- | --- |
| `exposition` | `content_md`, `next` | Read material, then continue |
| `choice` | `prompt_md`, `options`, `evaluator`, `feedback`, `transitions` | Select one answer and explicitly submit |
| `proof` | `prompt_md`, `hints`, `model_solution_md`, `rubric`, `evaluator`, `objective_id`, `next` | Draft, reveal optional help, snapshot attempt, self-review |
| `reflection` | `prompt_md`, `next` | Record optional free text and confidence |
| `end` | `summary_md` | End the unit without inferring mastery |

`choice.options` is a list of at least two `{id, text_md}` records with unique local option IDs. Its evaluator is exactly `{type: "exact_choice", expected: <option ID>}`. `feedback` has `correct_md` and `incorrect_md`; `transitions` has `correct` and `incorrect`, both activity IDs. A choice may have one `evidence` object, with exactly `objective_id` and `assessment_family`.

An evidence-bearing exact-choice activity must target an objective with skill `recall` or `recognition`. An assessment family is a globally namespaced stable ID shared by tasks that disclose essentially the same answer. Rewording or reordering choices does not create a fresh family. This keeps near-duplicate tasks from counting as independent demonstrations.

`proof.hints` is an ordered array of `{id, content_md}`. `rubric` is a nonempty list of `{id, criterion_md}`. Its evaluator is exactly `{type: "self_review"}` and its objective must have skill `proof_construction` or `proof_comprehension`. Empty hint arrays are allowed. The runtime stores rubric responses separately; no correctness key is inferred from the model solution.

Reflection text and confidence are optional learner input; the activity itself remains traversable with no answer. Confidence is `low`, `medium`, or `high`, or absent. It cannot produce checked correctness evidence.

## Execution semantics

The activity graph must be finite and acyclic in 0.1, with every node reachable from `entry` and every path ending at an `end` activity. All transition targets are local. Authors express remediation by branching forward to additional activities, not by adding cycles.

Retries are an engine affordance within the current choice activity. The learner sees feedback for a submission and may retry before selecting Continue; the branch is pinned to the **first submitted result**, so retries cannot bypass remediation. All submissions are retained. Back navigation is read-only history and does not resubmit or reset the active branch. Restarting a unit creates a new session, not a rewrite of old attempts.

A proof can be saved and resumed without revealing a solution. Continue requires an explicit submitted attempt or a deliberate “Skip for now” action. Skipping records no correctness evidence. Revealing the solution snapshots the current draft and records assistance; continuing records a self-review only when the learner actually supplies rubric responses.

Evidence is recorded when an answer is durably submitted, not when the next screen loads. `end` records session completion only. The app must preserve the current response if persistence fails.

## Markdown and mathematics

Allow paragraphs, headings below the activity title, emphasis, lists, code spans, code blocks, and tables. Disable raw HTML, images, remote embeds, and executable links. Version 0.1 content contains no clickable links; source citations are plain text. Display LaTeX source using a bundled renderer's approved subset and a readable fallback.

The documentation website has ordinary navigation links; its Markdown rendering rules are separate from imported course content. Never reuse a permissive documentation renderer as a package trust boundary.

## Update compatibility

Patch revisions may correct wording without changing keys, objectives, or mathematical meaning. Any answer-key, hypothesis, objective, rubric-meaning, or assessment-family change requires at least a minor unit version bump and a migration review. Major versions mark structural or educational incompatibility. These are author obligations, not something semantic version parsing can prove.

Version 0.1 transfers no derived evidence automatically to a different unit version. Old attempts remain visible and pinned to their original content. A future explicitly reviewed compatibility map may allow selective evidence reuse. Stable IDs preserve association; they do not justify reinterpreting old work.
