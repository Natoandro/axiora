# Decision log

These decisions establish the proposed design baseline dated 16 November 2026. “Adopted for design” means the documentation follows the decision; it does not mean the relevant software exists.

## ADR-001 — Offline core, optional AI

**Status:** adopted for design.

**Problem:** Advanced mathematics benefits from dialogue, but a network-dependent tutor introduces cost, availability, privacy, and correctness concerns into every session.

**Decision:** All required learning flows operate locally. AI authoring and tutoring are optional adapters with no authority to certify proofs or mutate learning evidence automatically.

**Tradeoff:** The first app cannot respond intelligently to every unexpected argument. It compensates with reviewed feedback, model solutions, rubrics, and preserved work.

**Reopen when:** Learner evidence identifies a recurring need that structured content cannot adequately address, with a viable privacy and assessment design.

## ADR-002 — Separate concepts, units, packages, and learner records

**Status:** adopted for design.

**Problem:** Textbook chapter order is not a reliable unit of learning or updating.

**Decision:** Concepts describe knowledge; units describe teaching; packages distribute immutable revisions; learner records retain personal work independently.

**Tradeoff:** More identifiers and explicit linking are needed. Content cannot simply be one mutable chapter file.

**Reopen when:** A concrete content case cannot be represented without redundant or ambiguous identity. Preserve historical anchors in any migration.

## ADR-003 — JSON runtime first, authoring DSL later

**Status:** adopted for design; revises the original YAML-first proposal.

**Problem:** Designing two formats before writing real units risks encoding assumptions that do not survive teaching practice.

**Decision:** Author the first units directly in strict runtime JSON with Markdown/LaTeX fields. Add a higher-level YAML or other authoring representation after repeated reviewed examples show the need.

**Tradeoff:** JSON is less pleasant for long prose. Early verbosity buys fewer semantic layers and a simpler compatibility story.

**Reopen when:** Three materially different reviewed unit types reveal stable repetition or authoring friction. A new authoring language must compile to the same normalized model.

## ADR-004 — Kotlin domain, native Android, JVM CLI

**Status:** proposed implementation baseline; no dependencies selected.

**Problem:** The reader and compiler should agree on validation and learning semantics without requiring a backend.

**Decision:** Share Android-compatible Kotlin domain and validation libraries between a Compose Android client and a JVM command-line compiler. Keep storage, UI, and provider adapters outside the core.

**Tradeoff:** This does not immediately support a browser client. Cross-platform extraction is deferred until a second real client exists.

**Reopen when:** A validated second-platform requirement or team constraint outweighs the cost of a shared Kotlin/JVM foundation.

## ADR-005 — Small acyclic activity graphs

**Status:** adopted for schema 0.1.

**Problem:** Unrestricted branching and loops make author validation, resumption, and evidence interpretation harder.

**Decision:** A unit is a finite directed acyclic graph. Retries are bounded by engine interaction semantics, and review creates separate work. Five activity types are supported initially.

**Tradeoff:** Some sophisticated tutoring strategies require duplicate authored nodes or separate units.

**Reopen when:** Reviewed courses require a specific cyclic pattern that cannot be expressed clearly otherwise. Define termination and persistence semantics before adding it.

## ADR-006 — Proof self-review is not automatic grading

**Status:** adopted for design.

**Problem:** A plausible-looking proof, or a learner's confidence in it, does not establish correctness.

**Decision:** First-release proofs use model solutions and rubrics. Store self-review as its own method with an unassessed correctness outcome. Exact-choice evidence can support recall/recognition only.

**Tradeoff:** Proof mastery remains a descriptive record requiring further judgment. The UI cannot offer a universal mastery score.

**Reopen when:** A constrained formal or symbolic evaluator can support a precisely defined task family. Keep verification method explicit and preserve older records.

## ADR-007 — Immutable revisions and conservative migration

**Status:** adopted for design.

**Problem:** Changing an answer key or hypothesis can make historical scores misleading.

**Decision:** Pin sessions and attempts to exact content revisions. A new unit version inherits no derived evidence automatically. Retain old work and snapshots; compatible IDs alone are insufficient.

**Tradeoff:** Updates can require rechecking known material and retaining old content bytes.

**Reopen when:** A reviewed compatibility map can justify selective evidence reuse with explainable, reversible transformations.

## ADR-008 — Transparent review rules before statistical adaptation

**Status:** adopted as a pilot policy, not an efficacy claim.

**Problem:** An elaborate mastery model can hide weak evidence behind a precise-looking percentage.

**Decision:** Use skill-specific descriptive states, distinct assessment families, first-submission evidence, and a simple interval ladder. Store policy versions and retain observations.

**Tradeoff:** The rules are coarse and may understate genuine knowledge. They are inspectable and easy to revise after observing learners.

**Reopen when:** Pilot evidence shows systematic misclassification or a tested alternative provides better calibration. Recompute projections without rewriting attempts.

## ADR-009 — Local records and explicit export

**Status:** adopted for first release.

**Problem:** Synchronization creates account, privacy, conflict, and operational requirements before the offline loop is proven.

**Decision:** Use local storage and explicit backup/restore. Restore into an empty profile only. No background telemetry or cloud service.

**Tradeoff:** Multi-device use and automatic disaster recovery are absent. Users must export their work deliberately.

**Reopen when:** Learners demonstrate a need for synchronization and its ownership, encryption, conflict, and recovery model has been designed.

## ADR-010 — One source for human and AI documentation

**Status:** implemented for documentation.

**Problem:** Independently written HTML and Markdown drift and can give implementers contradictory instructions.

**Decision:** Canonical Markdown generates static HTML, navigation, and a local search index. Commit HTML so reading needs no toolchain. Check links and generated freshness.

**Tradeoff:** Presentation changes belong in the generator and shared assets. Source-only internal details are not separately maintained.

**Reopen when:** A documentation requirement cannot be supported by the current generator without disproportionate complexity. Preserve one canonical source and offline reading.

## ADR-011 — ZIP container for the first package reader

**Status:** adopted for the current implementation; alternatives remain open.

**Problem:** A course revision needs one offline-importable artifact, bounded payloads, a manifest inventory, and byte-integrity checks.

**Decision:** Package separately named JSON payload files with a root manifest in a ZIP archive. Use ZIP STORE and deterministic metadata for the first reader. The compiler and reader reject unsafe archive paths and metadata before validating staged payloads.

**Tradeoff:** ZIP adds path, metadata, duplicate-entry, and expansion checks. A single JSON envelope could remove archive handling for small packages. A compiler-generated SQLite package is also viable and avoids manual database authoring, but it makes the database schema and SQLite compatibility part of the content contract.

**Reopen when:** Package scale and access patterns show that separate files provide little value, or when selective indexed reads justify a database package. Compare reader complexity and compatibility costs against the ZIP implementation before changing the contract. See [package container options](compiler.md#package-container-options).

## ADR-012 — Relational learner records and durable command receipts

**Status:** adopted for the JVM learner repository. Phase 5 also implements a framework SQLite driver. The Android vertical slice uses this driver. Its remaining device checks were waived for this milestone by ADR-015; coverage gaps remain documented in the implementation status record.

**Problem:** The headless engine can produce a new checkpoint, attempts, and events, but process restart currently loses them and replaying a command can apply it twice.

**Decision:** Store learner records in a versioned SQLite schema. The JVM adapter uses Xerial SQLite JDBC 3.54.1.0. A command transaction checks its durable receipt before invoking the transition callback, then commits the checkpoint, optional draft, attempts, events, projection invalidations, and versioned result payload together. Replays return the original payload. Package revisions are unique by package ID and version; a different manifest hash is a conflict.

**Phase 4 completion:** Schema 2 adds active-library membership and readable attempt context. Removal retains content and learner work; reimport reactivates explicitly. The application supplies prompt/key/rubric snapshots to the repository. Schema-1 records migrate with explicit unavailable-context text rather than invented historical detail. Backup format 2 accepts format-1 records with those defaults. Failed database registration leaves a validated promoted archive for startup reconciliation; exception and hard JVM-termination tests cover recovery. The phase introduces no evidence transfer across revisions.

**Phase 5 driver:** Use the Android framework SQLite API to reuse the schema, migrations, and backup format without packaging JDBC. The driver enables foreign-key enforcement and uses immediate transactions for writes. The application owns the driver and must run storage calls off the UI thread.

**Tradeoff:** Result and checkpoint payload encoding remain application responsibilities. Android cursor window limits can affect large records even when SQLite accepts their writes. Validate the supported record sizes and the minimum Android version before app acceptance.

**Reopen when:** Android compatibility or migration evidence requires a different driver or schema. Keep durable command identity, atomic primary-record writes, and immutable package identity across any replacement.

## ADR-013 — Framework views and Texlet for the Android vertical slice

**Status:** implemented in the local debug build. See the [device evidence](../../IMPLEMENTATION_STATUS.md#phase-5--android-rendering-and-storage-compatibility) for the acceptance gate.

**Problem:** Exercise the offline learning loop on the existing SDK and device setup with minimal additional dependencies.

**Decision:** Build the vertical slice with framework Android views, framework SQLite and the system document picker. Share the existing Kotlin modules and the native Texlet Canvas adapter. Advertise API 26 as the minimum candidate and compile against API 33. Keep selectable content source available even when a formula cannot be drawn or fits only as source.

**Tradeoff:** This defers the Compose choice in ADR-004. Course prose currently uses native text with supplemental formula previews. The handoff tracks richer Markdown presentation, localizable UI strings and device accessibility separately. The UI choice changes no package or backup format.

**Reopen when:** Device coverage or proof-workspace usability shows a concrete limitation of framework views. Keep the renderer data-only and preserve the exact revision and learner-record boundaries.

## ADR-014 — Course outlines separate from package inventory

**Status:** proposed for the next content schema. Schema 0.1 and its reader remain unchanged.

**Problem:** The Library needs course identity and ordered units, with headings for longer outlines. The package manifest also has to describe an immutable archive and its exact payload, including unit revisions.

**Decision:** Use Course → Section → Unit → Activity as the package structure. Deliver one explicit course descriptor per package. Require an ordered list of sections in each descriptor. A single untitled section displays as a plain unit list. Sections carry no learner identity or state. The manifest inventories exact revisions, and each unit owns its local activity graph.

**Tradeoff:** Authors maintain both course order and archive inventory, so the compiler must validate agreement. One course per archive keeps installation and revision selection bounded while the first real course outlines are reviewed.

**Compatibility:** Read schema 0.1 as an implicit course keyed by package ID, with all manifest units in one untitled section. An upgraded descriptor can preserve that Library identity by adopting the old package ID as its course ID. Preserve exact package and unit references in learner records.

**Reopen when:** Reviewed content requires shared units across separately distributed courses, multiple courses shipped together, or semantic sections with their own objectives.

## ADR-015 — Accept the Android vertical slice with device checks waived

**Status:** accepted by the owner for this prototype milestone on 18 November 2026. This does not claim the waived checks passed.

**Problem:** Phase 5 has a signed, installable Android vertical slice and prior API 30 offline flow evidence, but the available environment does not provide TalkBack, API 26, representative ARM64, or tablet targets. A fresh offline end-to-end run could not be established during the final recheck.

**Decision:** Stop the remaining device checks at the owner's direction. Accept Phase 5 and close M3 with the unverified checks recorded as limitations. Keep their evidence state explicit; do not report them as passed. Course review and pilot work remain Phase 6.

**Tradeoff:** The milestone can proceed without waiting for unavailable devices, while accessibility, minimum-version, ARM64, tablet, and fresh offline-loop risks remain unresolved. This acceptance is for the local prototype and does not establish release readiness or replace a future device matrix.

**Affected scope:** The Phase 5 device-acceptance exit criteria and M3 milestone disposition in `IMPLEMENTATION_PLAN.md` and `IMPLEMENTATION_STATUS.md`. No package, learner-data, or application behavior contract changes; no data migration is required.

**Reopen when:** Before an external pilot or release, run the waived TalkBack, API 26, ARM64, tablet, and offline end-to-end checks on recorded targets, resolve blockers, and update the acceptance record. Preserve all earlier evidence and distinguish new results from the waived checks.
