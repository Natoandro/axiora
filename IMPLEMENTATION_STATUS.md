# Implementation status

This record tracks verified work against [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Design contracts in `docs/markdown/` remain authoritative. Phases 1–4 are complete at the JVM layer; M1 and M2 are closed. Phase 5 has started with an Android renderer prototype. M3 remains open and course review remains pending.

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

## Phase 4 — complete at the JVM layer

**Environment:** OpenJDK 21.0.13, Gradle 8.14, Kotlin Gradle plugin 2.2.20, SQLite JDBC 3.54.1.0, JVM bytecode target 17; Linux temporary filesystems and real SQLite databases. No new dependency was introduced.

**Implemented:** versioned checkpoints and command-result codecs, `StudyRepository`, SQLite schema 2, immutable package storage, staged installation and startup reconciliation, active-library removal, standalone readable attempt history, notes, consistent application-level JSON backups, and atomic empty-profile restore. Command transactions write receipts, checkpoints, drafts, attempts, assistance, ordered events, observations, and summary invalidations together. Rejected stale draft commands no longer attempt a draft-table write. New sessions require an active package; existing sessions remain pinned across updates and removal. The application supplies prompt/key/rubric context for new attempt snapshots.

**Recovery and compatibility:** Injected failures cover before/after checkpoint, receipt, draft, attempt, observation, event, projection, and commit boundaries; failure preserves the prior export and permits retry. Installer failures cover staging, copy, validation, promotion, and registration. Valid promoted archives survive database failure and are registered on reconciliation; abandoned staging files are removed. A frozen populated schema-1 SQL fixture migrates without losing learner records. Format-1 backups upgrade with explicit unavailable-context text for older attempts. New exports use format 2. Summary invalidation/rebuild leaves primary records intact. No automatic content garbage collection or learner-data deletion is introduced.

| Gate | Verified evidence |
| --- | --- |
| C01–C02 | Same import is idempotent; changed identity bytes are refused with the installed revision preserved |
| L02 | Production repository replays the original submission after reopen and restore with one historical attempt |
| D01 at storage layer | A separate JVM halts during a draft transaction; reopen restores the prior committed snapshot. Repository integration also verifies failed-save rollback, later revision, and stale-autosave refusal |
| D02 | New package/unit versions install beside the old archive; old session stays pinned and new repository work uses the new revision |
| D03 | Deactivation blocks new sessions while retaining archives, draft, notes, readable prompt/response/assistance history, and existing-session resume; reconciliation does not reactivate removal |
| B01 | Every table round trips, including drafts, notes, attempts, assistance, event sequences, observations, summaries, reviews, command identities, active flags, and content references; missing packages are reported |
| B02 | Corrupt JSON, duplicate keys, trailing JSON, wrong version type, relational corruption, and nonempty-profile restores refuse without changing prior work |
| Crash recovery | Separate JVMs halt without cleanup during draft writes and after archive promotion; reopening/reconciliation recovers safely |
| Migration | Populated schema-1 fixture upgrades; reopening is idempotent; future database schemas are refused; format-1 backups retain work |

**Verified commands (17 November 2026):**

- `GRADLE_USER_HOME=/tmp/axiora-gradle ./gradlew --no-daemon test ktlintCheck` — passed for all JVM modules: 50 tests in the recorded reports, including 18 storage tests; unchanged module tasks were up to date.
- `UV_CACHE_DIR=/tmp/axiora-uv-cache uv run --locked python scripts/build_docs.py --check` — passed.
- `UV_CACHE_DIR=/tmp/axiora-uv-cache uv run --locked python scripts/check_docs.py` — passed.

**Remaining outside Phase 4:** Android driver compatibility, file-selection and privacy UI, visible save status, actual Android force-stop/lifecycle behavior, device accessibility, and renderer integration remain Phase 5. JVM process termination does not establish Android acceptance or power-loss/filesystem durability on every device. Missing content prevents session resume until exact content is available; historical attempt snapshots and notes remain readable. Older schema-1 attempts cannot recover unsaved prompt context. Mathematical and teaching review remain outstanding. No Android application, AI integration, or reviewed course is claimed.

## Phase 5 — Android renderer spike in progress

The [Android math spike](spikes/android-math/README.md) builds and signs an isolated APK with the installed SDK. It exercises native text previews for fixture notation, plain-language descriptions, selectable source, and an unsupported-formula fallback. APK signature verification passed. The original Pixel 3a API 30 AVD stalled before boot. A fresh AVD using the same system image reached boot completion, installed and launched the APK, and exposed both expected previews and the unsupported-formula fallback in the UI hierarchy. On the visible API 30 emulator, the long preview wrapped at 320 dp and 2.0 font scale, the fallback remained reachable by scrolling, and two Tab presses reached the first preview and its source. TalkBack, tablet coverage, and the typeset renderer remain unverified. API 26 and framework SQLite are candidates for the study app. Neither the storage adapter nor lifecycle integration has been verified on Android.

The standalone Rust Texlet prototype lays out basic single-row expressions, including `\in` and a single uppercase letter in `\mathbb{…}`, using a caller-provided math font. Its SVG adapter writes glyph outlines from the same font. The preview `x \in \mathbb{Z}` was rendered and visually checked with Latin Modern Math. The Rust renderer has yet to be integrated with Android. The [notation roadmap](crates/texlet/ROADMAP.md) records the supported subset and proposed expansion.
