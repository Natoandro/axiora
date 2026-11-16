# Implementation status

This record tracks verified work against [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Design contracts in `docs/markdown/` remain authoritative. Phases 1–3 are complete; M1 and M2 are closed. Durable learner storage, package installation, Android, and the reviewed course remain later work.

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

**Implemented so far:** the new `storage` JVM module defines SQLite schema version 1 for immutable package revisions, pinned sessions, command results, drafts, attempts, ordered learning events, observations, objective summaries, review tasks, and notes. The adapter registers package revisions idempotently by identity and manifest hash, rejecting a conflicting hash. Its command transaction reads an existing receipt before calling the transition callback, then commits the updated checkpoint, optional draft, attempts, events, summary invalidations, and versioned result payload together. Draft writes only replace a stored draft with a higher revision. The Xerial SQLite JDBC version and scope are recorded in [ADR-012](docs/markdown/decisions.md#adr-012--relational-learner-records-and-durable-command-receipts).

**Not yet implemented:** a codec and repository facade connecting `StudyEngine` state to durable snapshots; full observation and review persistence; staged package installation/reconciliation; backup/restore; and migration fixtures. The JVM driver does not verify Android compatibility.

**Verification:** `:storage:compileKotlin :storage:ktlintCheck` could not start: Gradle failed before project configuration with “Could not determine a usable wildcard IP for this machine.” No database tests were added or run. The schema and transaction behavior are therefore not yet verified against a real database, and Phase 4 remains open.
