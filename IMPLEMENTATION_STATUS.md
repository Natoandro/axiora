# Implementation status

This record tracks verified work against [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Design contracts in `docs/markdown/` remain authoritative. Phases 1–3 are complete; M1 and M2 are closed. Phase 4 implementation is underway but remains unverified; Android and the reviewed course remain later work.

## Phase 1 — complete

**Environment:** OpenJDK 21.0.13; Gradle wrapper 8.14; Kotlin Gradle plugin 2.2.20; content bytecode target 17. Jackson 2.22.0 enforces duplicate-key rejection while parsing JSON. The CLI uses Gradle's Application distribution.

**Implemented:** `content-model`, `content-validation`, and `compiler-cli` JVM modules; strict duplicate-key parsing; bounded UTF-8 JSON reads; field, type, version, capability, identity, inventory, hash, reference, evaluator/objective, prerequisite-cycle, and activity-graph checks; read-only `validate` text and JSON output. The illustrative package has three distinct units for prerequisite recognition, the prime/maximal counterexample, and proof construction followed by a fresh check. All three remain explicitly draft content.

**Verified:**

- `./gradlew test :compiler-cli:installDist ktlintCheck` — passed; includes the validator edge cases and CLI output/exit-code coverage.
- `compiler-cli/build/install/axiora/bin/axiora validate examples/prime-and-maximal` — passed, exit 0.
- `compiler-cli/build/install/axiora/bin/axiora validate examples/prime-and-maximal --format json` — passed, exit 0, valid JSON with no diagnostics.
- `UV_CACHE_DIR=/tmp/axiora-uv-cache uv run --locked python scripts/build_docs.py --check` — passed.
- `UV_CACHE_DIR=/tmp/axiora-uv-cache uv run --locked python scripts/check_docs.py` — passed; four payloads and fourteen activities checked.

**Exit-gate coverage:** tests cover unreachable nodes, activity cycles and nonterminating paths, missing targets, prerequisite cycles, unsupported assessment claims, required capability declarations, duplicate JSON keys, unsupported schema, and CLI text/JSON output with exit codes 0, 1, and 2. Shared JVM libraries have no Android, database, network, or AI dependency.

**Remaining in Phase 1:** none. Phase 2 archive creation and hostile archive reading are complete below. Mathematical and teaching review, durable storage, Android, and external distribution remain later-phase work.

## Phase 2 — complete

**Implemented:** `pack` validates the source, serializes JSON with sorted object keys, sorts archive paths, hashes final payload bytes, and writes a fixed-time ZIP STORE archive plus an adjacent build report. Existing output requires `--overwrite`; output must be outside the authoring directory. `inspect` checks bounded archive size, entry count, UTF-8 names, central and local ZIP metadata, path and case collisions, regular-file metadata, stored-entry size limits, and streamed expansion limits. It stages temporarily, checks UTF-8 JSON and nesting limits, and runs the shared Phase 1 validator before returning a revision descriptor. Installation is not included.

**Verified:** `GRADLE_USER_HOME=/tmp/axiora-gradle ./gradlew --no-daemon test :compiler-cli:installDist ktlintCheck` — passed in the recorded run and rechecked on 16 November 2026. CLI `validate → pack → inspect` passed for the illustrative fixture. Two unchanged builds compared byte-identical with `cmp`; inspect reported package `axiora.ca.first-slice@0.1.0`, four payloads, and 11,353 uncompressed bytes. CLI integration tests cover pack and inspect. Hostile reader tests reject traversal, symlink metadata, duplicate filenames and JSON keys, case collisions, encryption, malformed UTF-8 names and payloads, central/local size mismatches, CRC-corrupt payloads, unknown capabilities, corrupt hashes, undeclared files, compressed expansion, and oversized archives. Tests verify the packer leaves the source manifest unchanged.

**Handoff:** deterministic `.axp` artifacts, external build reports with source hashes and diagnostics, a bounded shared reader, revision descriptors, CLI integration, and hostile archive tests. Installation remains Phase 4 work. Hashes establish byte integrity only; they do not authenticate authorship or review claims. Mathematical and teaching review remain outstanding.

## Phase 3 — complete

**Implemented:** `learning-core` provides injected unit lookup, clock, local-zone offset, and ID sources; pinned unit revisions; typed commands and transition errors; immutable choice and proof attempts; assistance snapshots; learning events; choice retry behavior that preserves the first branch; proof drafts and self-review; rubric recording; reflection, skip, pause, resume, and completion transitions. Reused command IDs are rejected by the domain state; a future storage adapter must keep durable command receipts and return the original result without applying the command again.

Evidence policy produces versioned observations from exact-choice attempts, retaining objective, skill, assessment family, first-submission flag, assistance, UTC time, local study date, and zone offset. Only unassisted first-submission exact-choice recall/recognition observations are eligible. Demonstration requires two correct observations from distinct families and local dates at least 24 hours apart; proof self-review remains unassessed. Backward timestamps suspend evidence promotion. Review scheduling implements the 1/3/7/14/30-day ladder, due-date-only advancement, fresh-family checks, eligible-error reset, task deduplication, and a three-item recommendation limit.

**Verified:** `GRADLE_USER_HOME=/tmp/axiora-gradle ./gradlew --no-daemon test ktlintCheck` — passed on 16 November 2026. Tests cover incorrect/correct retry with first-branch preservation; hinted proof self-review; skipped-proof completion without fabricated attempts; deterministic replay with fixed IDs and time; evidence eligibility and two-family/two-date/24-hour rules; clock rollback; time-zone date snapshots; review scheduling and deduplication; and recommendations. The end-to-end headless session harness exercises branching, retry, hint, rubric, reflection, and completion paths. Phase 3 exits pass; M2 is closed.

**Remaining:** durable receipts and atomic persistence for repeated commands (Phase 4), package installation into learner storage (Phase 4), and any Android execution remain unimplemented. Mathematical and teaching review of the example content remains outstanding.

## Phase 4 — in progress

**Implemented and focused checks passed:** the storage module now has a binary, versioned study checkpoint codec that reloads the exact pinned unit, a replay result codec, and a `StudyRepository` that checks durable command receipts before invoking `StudyEngine`. Accepted command records, attempts, evidence observations, draft writes, event rows, summary invalidations, and the new checkpoint are committed in one transaction. The store has checkpoint and note reads/writes, package identity registration, and versioned JSON backup snapshots that restore only into an empty profile. `PackageInstaller` validates archives before staging and atomic promotion, and `reconcile` registers promoted archives left behind by a crash while reporting missing files. Tests cover checkpoint/result round trips, command replay, stale-draft rollback, backup restore/refusal, package idempotence, conflicting identity, and reconciliation.

**Verified:** `GRADLE_USER_HOME=/tmp/axiora-gradle ./gradlew --no-daemon :storage:ktlintFormat :storage:test :storage:ktlintCheck` passed on 17 November 2026. This compiled the storage module and passed its six real-SQLite tests and formatting checks.

**Still open:** additional acceptance coverage is needed for failures at every transaction and file-promotion boundary, backup retention of all record types and missing packages, and the full D01–D03 import/update/removal paths. No migration fixture exists because schema version 1 is the first schema. Android driver and lifecycle compatibility remain Phase 5 work. Phase 4 remains open until its full exit criteria pass.
