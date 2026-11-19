# Axiora implementation plan

**Status:** active execution plan · 18 November 2026. Phases 1–4 are complete at the JVM layer. Phase 5 and M3 were accepted by owner waiver with device checks still unverified; see [ADR-015](docs/markdown/decisions.md#adr-015--accept-the-android-vertical-slice-with-device-checks-waived) and the [implementation status record](IMPLEMENTATION_STATUS.md). Course review remains pending.

This plan turns the [delivery milestones](docs/markdown/delivery.md) into reviewable work. The detailed [content contract](docs/markdown/content.md), [learner-state contract](docs/markdown/learner-state.md), and [decision log](docs/markdown/decisions.md) remain authoritative. A phase is complete when its observable exit criteria pass or an owner-approved deviation is recorded. Waived checks must remain explicitly unverified; creating directories or screens is insufficient.

## Target and boundaries

Deliver one complete offline learning loop: import a reviewed course slice about prime and maximal ideals, study a branching unit, write and resume a proof, record self-review, return for a fresh check, and export and restore personal work.

Use the proposed Kotlin/JVM domain libraries, JVM compiler CLI, native Android client, and SQLite persistence. Select and verify dependency versions against official documentation when implementation starts. Keep Python and uv for the existing documentation tooling.

No backend, account, cloud synchronization, arbitrary PDF ingestion, general theorem prover, or runtime AI is required. Completion, correctness, assessment method, assistance, and learning evidence remain separate throughout implementation.

## Phase map

| Phase | Milestone | Main result | Dependency |
| --- | --- | --- | --- |
| 0 | M0 — complete | Design, handbook, illustrative fixture, documentation checks | None |
| 1 | M1 | Strict executable content model and author validation | Phase 0 |
| 2 | M1 | Reproducible packages and safe archive reader | Phase 1 |
| 3 | M2 — complete | Deterministic headless study and review engine | Phase 1; Phase 2 for packaged end-to-end verification |
| 4 | M3 | Durable local storage, import recovery, backup and restore | Phases 2–3 |
| 5 | M3 — accepted by waiver | Android learning loop; device gaps remain open | Phase 4; rendering spike may begin during Phase 3 |
| 6 | M4 | Reviewed course slice and local pilot | Phase 5; content drafting begins during Phase 1 |
| 7 | M5 — conditional | Optional authoring assistance | Phase 6 and evidence of authoring need |

There are no calendar commitments yet. Estimate each phase after its preceding gate, using actual implementation and content-review effort. Technical checks do not establish mathematical correctness or teaching effectiveness.

## Phase 1 — Make the content contract executable

**Outcome:** authors can validate runtime JSON using a shared Kotlin library and a local CLI.

### Initial implementation record

- Build: Gradle 8.14 wrapper, Kotlin Gradle plugin 2.2.20, JDK 17 bytecode target. The local environment uses JDK 21. These versions were checked against the [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html), [Gradle Wrapper guide](https://docs.gradle.org/current/userguide/gradle_wrapper.html), and [Kotlin release process](https://kotlinlang.org/releases.html).
- JSON: Jackson 2.22.0 tree parsing with `STRICT_DUPLICATE_DETECTION`; no Kotlin data binding is used at the trust boundary. The validator checks exact object fields and types before producing typed content models.
- CLI: Gradle Application plugin distribution, initially invoked from `compiler-cli/build/install/axiora/bin/axiora`. It supports the documented `validate` text and JSON formats; packaging is not included yet.
- Validation layers: JSON syntax, UTF-8, exact fields, supported versions/capabilities, and bounds are structural; IDs, inventory/hash consistency, references, objective/evaluator compatibility, prerequisite cycles, and activity graph properties are semantic. Review status remains an author claim and is never authenticated by this tool.
- Verified commands are listed in [README.md](README.md). The Phase 1 exit gate is complete; the verified implementation is recorded in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

Work in this order:

1. Establish a Gradle Kotlin/JVM build with a wrapper and the initial `content-model`, `content-validation`, and `compiler-cli` modules. Keep Android, storage, and network dependencies outside domain code. Document build and test commands when introduced.
2. Record the selected toolchain, strict JSON/schema tooling, and initial CLI distribution method. Define which rules belong to schema validation and which require semantic validation.
3. Implement identifiers, revision types, concepts, manifests, objectives, evaluator declarations, and the five activity types: exposition, choice, proof, reflection, and end.
4. Implement strict parsing and structural checks, including duplicate JSON keys, field shapes, supported versions and capabilities, bounded values, and explicit unsupported-input diagnostics.
5. Implement references, objective/evaluator compatibility, assessment families, prerequisite cycles, and activity-graph validation: valid entry, reachable nodes, no cycles, and terminal paths.
6. Implement `validate` with text and JSON diagnostics and the documented exit codes. Keep validation read-only.
7. Expand the authoring examples toward three distinct units: prerequisite recognition, the prime/maximal distinction and counterexample, and proof construction with a later fresh check. Keep review status honest; use their differences to challenge the model before freezing it.

**Verification and exit gate:**

- The existing fixture validates through the production validator; any contract corrections update the example and documentation together.
- Invalid fixtures produce stable diagnostic codes and useful file/field paths for each validation layer.
- Graph tests cover unreachable nodes, cycles, missing targets, and nonterminating paths; assessment tests reject unsupported evidence claims.
- Text and JSON CLI output and exit codes have integration coverage.
- Shared libraries run without Android, a database, network access, or an AI provider.

**Handoff:** executable models, validator, CLI validation command, representative fixtures, and documented local build commands. No archive safety or mathematical review claim yet.

## Phase 2 — Build reproducible packages and a safe reader

**Outcome:** the CLI produces immutable `.axp` archives that a shared reader can inspect safely.

1. Implement `pack` and `inspect`, reusing Phase 1 validation. Include only the manifest and declared payloads.
2. Normalize output according to the compiler contract: deterministic serialization, sorted paths, fixed ZIP metadata, ZIP STORE, and SHA-256 over final payload bytes. Write the build report outside the archive.
3. Enforce the documented archive and payload limits while reading, before allocating or extracting unbounded content. Treat names, paths, metadata, and text as untrusted data.
4. Reject traversal, absolute paths, symlinks, duplicate or colliding paths, undeclared files, invalid encodings, corrupt hashes, unsupported capabilities, and oversized input.
5. Produce a validated revision descriptor suitable for installation. Keep filesystem/database installation in Phase 4 rather than hiding it inside validation.

**Verification and exit gate:**

- Repeated builds from unchanged inputs with the same toolchain and policy are byte-identical.
- `validate → pack → inspect` succeeds for the representative fixtures.
- Hostile archive tests cover C03 in the [acceptance scenarios](docs/markdown/delivery.md#acceptance-scenarios), including declared-size mismatches and bounded reading of compressed inputs.
- Invalid input never writes outside staging, exposes a partial installed revision, or changes source files.
- Hashes are described as integrity checks, without implying authenticated authorship.

**Handoff:** reproducible CLI artifacts, shared archive reader, diagnostic corpus, and hostile input tests. This closes M1.

## Phase 3 — Implement the headless study engine

**Outcome:** a complete unit runs deterministically without Android or persistent storage.

### Implementation record

- `learning-core` implements injected unit lookup, clock, local-zone offset, and ID sources; pinned revisions; typed commands and explicit transition errors; immutable choice/proof attempts; assistance snapshots; append-only domain events; and pause/resume/completion transitions.
- Choice attempts become versioned objective observations with assessment family, first-submission status, assistance, UTC timestamp, local study date, and UTC offset. Only unassisted, first-submission exact-choice recall/recognition outcomes are eligible. Demonstration requires two correct eligible observations from distinct families and local dates at least 24 hours apart. Backward event timestamps suspend promotion; proof self-review stays unassessed.
- The review policy uses the 1, 3, 7, 14, and 30 day ladder, keeps practice before a due date from advancing it, requires a different family at a due check, resets on an eligible due error, deduplicates by objective/revision/policy, and returns at most three plain-language recommendations. Policies are deterministic functions over supplied facts; storage transactions and durable command receipts remain Phase 4.
- Domain tests include an end-to-end branching session through incorrect, retry, hinted proof, rubric self-review, reflection, and completion; the skipped-proof completion path; deterministic replay with fixed clock and IDs; distinct-family/date evidence; retry/assistance exclusion; review ladder and deduplication; local-date/zone snapshots; and backward-clock handling.

1. Create `learning-core` with typed commands, session/activity states, immutable attempts, assistance snapshots, events, and explicit transition errors. Inject clock, IDs, and content lookup.
2. Implement all five activity types. Preserve the first submitted choice branch across retries. Separate proof draft edits, submission, rubric self-review, skipping, and completion.
3. Return state changes and facts for a storage transaction. Define repeated-command behavior so storage can durably return the original result rather than apply the command twice.
4. Implement exact-choice evaluation and unassessed proof self-review. Unsupported evaluation must preserve the response without guessing correctness.
5. Implement versioned observations, descriptive summaries, and evidence eligibility. Apply the documented distinct-family, first-submission, assistance, date, and elapsed-time rules.
6. Implement the review ladder, task deduplication, clock-anomaly handling, and recommendations. Explain when no fresh assessment family is available.
7. Add a headless runner or integration harness that plays the fixture through correct, incorrect, retry, hinted, skipped, and self-review paths.

**Verification and exit gate:**

- `GRADLE_USER_HOME=/tmp/axiora-gradle ./gradlew --no-daemon test ktlintCheck` passes for all JVM modules.
- Deterministic replay produces the same state and events for the same inputs, clock, and IDs.
- L01, L03, P01, P02, R01, and R02 pass at the domain level; repeated-command semantics for L02 are defined and tested, with durability verified in Phase 4.
- Completion cannot fabricate proof correctness or demonstrated mastery.
- Review scheduling and evidence promotion are tested separately, including midnight, elapsed time, zone changes, and backward/forward clock changes.
- The miniature fixture cannot demonstrate mastery using its single recognition family.

**Handoff:** executable domain engine and acceptance tests. M2 is closed. Package-to-engine session installation remains an integration check for a later vertical slice; storage durability is Phase 4.

## Phase 4 — Preserve learner work and recover safely

**Outcome:** the same engine runs over durable SQLite records and immutable package storage.

### Implementation record — complete at the JVM layer

- The `storage` JVM module uses SQLite JDBC 3.54.1.0 and schema version 2. It stores immutable package revisions, pinned sessions, command results, drafts, attempts, ordered events, observations, summaries, review tasks, and notes.
- `StudyRepository` now connects `StudyEngine` to the transaction API. A binary versioned checkpoint codec resolves exact pinned content through `UnitLookup`; a versioned result codec preserves accepted/rejected command results. Durable receipt lookup happens before reduction. The transaction commits the checkpoint, draft, attempts, observations, events, summary invalidations, and versioned result together. Draft updates require a strictly newer revision.
- `PackageInstaller` validates the archive, stages it under the library, atomically promotes the immutable revision, and registers it in SQLite. Startup reconciliation registers promoted archives after a crash and reports database records whose archive is missing. The application-level JSON backup contains learner records and content references, excludes package bytes, enforces size and shape limits, and restores only into an empty profile. Note persistence is exposed through the store.
- Real-SQLite tests now cover every command-write boundary, draft rollback and revision ordering, durable replay after reopen/restore, all backup record types, missing packages, corrupt/nonempty restore refusal, version-1 backup compatibility, and a frozen populated schema-1 migration fixture. Installer tests cover staging/copy/validation/promotion/registration failures, identity conflicts, updates, active-library removal, reconciliation, and abandoned staging cleanup. Separate JVMs halt during a draft transaction and after archive promotion to verify recovery without cleanup callbacks.
- The full `GRADLE_USER_HOME=/tmp/axiora-gradle ./gradlew --no-daemon test ktlintCheck` acceptance gate passed on 17 November 2026; see [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for scenario coverage and limits. The adapter remains JVM-only; Android driver, UI save indicators, and lifecycle integration remain Phase 5. See [ADR-012](docs/markdown/decisions.md#adr-012--relational-learner-records-and-durable-command-receipts).

1. Implement the `storage` adapter and versioned relational schema for package revisions, sessions, command results, drafts, attempts, events, observations, summaries, reviews, and notes.
2. Commit command results, primary records, checkpoint changes, and projection updates or invalidations atomically. Enforce command-ID uniqueness and monotonically increasing draft revisions.
3. Implement staged installation and startup reconciliation across filesystem promotion and database registration. Same-package import is idempotent; conflicting bytes under the same identity/version are rejected.
4. Pin sessions to exact revisions. Retain referenced content and readable attempt snapshots. Separate removal from the active library from explicit learner-data deletion.
5. Implement versioned application-level backup and empty-profile restore. Validate in staging, preserve IDs and references, report missing packages, and reject unsafe or incompatible backups without altering existing work.
6. Establish migration fixtures and recovery tests before evolving the database schema. Preserve primary records when rebuilding derived summaries.

**Verification and exit gate:**

- C01–C02, L02, D01–D03, and B01–B02 pass against a real database and temporary filesystem.
- Injected failures around transaction and import promotion boundaries leave a recoverable state and preserve the prior installation.
- A stale autosave cannot overwrite a newer draft; failed writes cannot appear saved or advance the durable session.
- Backup round trips retain drafts, notes, attempts, assistance, command identities, and content references, including when packages are unavailable.
- Migrations are tested against saved records from each supported prior schema, once such schemas exist.

**Handoff:** durable repositories, installer, recovery protocol, backup format, and persistence integration tests.

## Phase 5 — Deliver the Android vertical slice

**Outcome:** a learner can complete the target loop on a device in airplane mode.

1. Run a focused mathematical-rendering and accessibility spike before building all screens. Select supported notation, fallback behavior, Android minimum version, device targets, and SQLite adapter; record consequential decisions. If a local WebView is needed, verify the restrictions in the architecture contract.
2. Create `android-app` and wire the existing engine and storage adapters through a small application layer.
3. Implement library and import preview, unit navigation, choice feedback and remediation, proof editor, hints/model solution/rubric, reflection, completion, and due review.
4. Implement lifecycle-safe draft saving, truthful saved/unsaved state, recoverable storage errors, and resume after process termination. Keep typed text visible when saving fails.
5. Add revision-update choices, retained historical work, explicit export/restore, and clear missing-content states through user-selected file access.
6. Verify large text, TalkBack, keyboard focus, readable math source, and narrow and tablet layouts. Show assessment method and assistance without presenting an invented universal mastery score.

**Verification and exit gate:**

- All M1–M3 acceptance scenarios pass at the appropriate layer, with device coverage for import, resume, reviews, updates, and backup/restore.
- D01 includes actual process termination and reopen, not only simulated navigation.
- A01 and O01 pass on the recorded device matrix. All required flows work without an account, remote assets, or network calls.
- Renderer failures expose readable source without losing session state; untrusted content cannot navigate externally or invoke privileged app behavior.
- A reproducible local demo completes import → study → proof draft → close/reopen → self-review → later fresh check → backup/restore.

**Handoff:** installable Android build, device validation record, known limitations, and instructions for reproducing the demo. This closes M3; it is not yet a reviewed course release.

**Owner disposition (18 November 2026):** Phase 5 and M3 are accepted under [ADR-015](docs/markdown/decisions.md#adr-015--accept-the-android-vertical-slice-with-device-checks-waived). TalkBack, API 26, representative ARM64, tablet, and a fresh offline end-to-end run remain unverified. This waiver does not establish release readiness; complete those checks before an external pilot or release.

## Phase 6 — Review the course and run a pilot

**Outcome:** a small reviewed course and observed learner use establish what to improve next.

1. Complete the three-unit course slice begun in Phase 1. Provide fresh assessment families for checked objectives, clear prerequisite guidance, original explanations, counterexamples, feedback, rubrics, and source provenance.
2. Record mathematical and teaching review with scope, reviewer, revision, findings, and resolution. Do not promote draft fixtures to reviewed content based on compiler success.
3. Revalidate and package reviewed revisions. Verify that the content supports delayed independent checks rather than immediate repetitions presented as new evidence.
4. Prepare pilot tasks covering comprehension, first proof attempt, remediation, resumption, and return review. Resolve content rights and software licensing before external distribution; obtain separate authorization for publication or contacting participants.
5. Observe a small pilot with deliberately collected feedback rather than background telemetry. Record authoring/review effort, learner confusion, accessibility barriers, save failures, and misleading assessment signals.
6. Prioritize and resolve blocking data-loss, mathematical, assessment, and usability findings. Repeat affected checks and document remaining limitations.

**Verification and exit gate:**

- Every distributed unit has traceable provenance, rights, and scoped review status.
- No unresolved blocker threatens learner work or misrepresents correctness and evidence.
- The pilot report distinguishes observed usability from unproven long-term learning effectiveness.
- A prioritized follow-up backlog explains whether to deepen the course, improve the engine/interface, or invest in authoring tools.

**Handoff:** reviewed course revision, acceptance record, pilot findings, and a justified next milestone. This closes M4.

## Phase 7 — Add authoring assistance only when justified

**Entry gate:** Phase 6 identifies a concrete authoring bottleneck and a bounded task that assistance could improve. Otherwise defer this phase.

1. Accept selected, permitted source material or a single objective; generate draft units outside learner packages.
2. Record source locators, extraction quality, provider/model, prompt-template version, generation settings, and review outcomes.
3. Run all drafts through the existing validator. Present changes for human review without silently modifying the concept catalog or declaring mathematical truth.
4. Compare authoring time, correction effort, and review quality against manual authoring before expanding the feature.

**Exit gate:** generated drafts remain explicitly unreviewed until scoped human review, secrets and private source files stay out of packages, and measured benefit justifies ongoing complexity. Optional conversational tutoring requires its own privacy and assessment design; it is a separate decision.

## Working and completion rules

- Ship small reviewable changes within each phase: one rule family, command path, recovery behavior, or usable screen flow at a time.
- Begin each change with the relevant contract and an observable acceptance case. Add meaningful tests for behavior and failure paths; avoid tests that merely mirror static declarations.
- Update models, examples, compatibility notes, and decisions together when a contract changes. Do not silently carry evidence across content revisions.
- Keep a phase acceptance record listing what passed, the environment, remaining defects, and any recorded departures. Unmet exit criteria keep the phase open unless an owner-approved deviation explicitly accepts the gap.
- Continue the existing documentation freshness and link checks. Document new build and test commands only when they actually exist.

## Package tooling port — in progress

The Rust package crate and CLI provide initial schema-0.1 validation and archive operations. Android uses the Rust JNI reader for archive inspection, loading, and stored-revision verification, while Kotlin still constructs typed content models and repeats validation. The Kotlin CLI remains the production CLI. Complete cross-implementation compatibility, JVM caller and host distribution decisions, Android device verification, and cutover before treating this work as complete. See the [implementation status](IMPLEMENTATION_STATUS.md#rust-package-tooling-port--in-progress) for verified progress and open gates.

## Next concrete change

Proceed to Phase 6: complete scoped mathematical and teaching review of the three-unit course slice, then prepare a local pilot plan. Keep the Phase 5 device gaps visible and resolve them before an external pilot or release. Do not describe hashes as authenticated authorship.
