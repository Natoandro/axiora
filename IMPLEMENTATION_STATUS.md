# Implementation status

This record tracks verified work against [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Design contracts in `docs/markdown/` remain authoritative. Phases 1–4 are complete at the JVM layer; M1 and M2 are closed. Phase 5 has an installable Android vertical slice. M3 remains open for device acceptance and course review remains pending.

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

## Phase 5 — Android rendering and storage compatibility

The [Android math spike](spikes/android-math/README.md) builds and signs an isolated APK with the installed SDK. It exercises native text previews for fixture notation, plain-language descriptions, selectable source, and an unsupported-formula fallback. APK signature verification passed. The original Pixel 3a API 30 AVD stalled before boot. A fresh AVD using the same system image reached boot completion, installed and launched the APK, and exposed both expected previews and the unsupported-formula fallback in the UI hierarchy. On the visible API 30 emulator, the long preview wrapped at 320 dp and 2.0 font scale, the fallback remained reachable by scrolling, and two Tab presses reached the first preview and its source. TalkBack, tablet coverage, and the typeset renderer remain unverified. API 26 remains the candidate minimum for the study app.

The Rust Texlet prototype implements the [Tier 1 notation subset](crates/texlet/ROADMAP.md), including nested fractions, radicals, scripts, math alphabets, and inline and display operators. The SVG adapter renders glyph outlines and rules from the layout, and the Android spike draws the same commands through its JNI Canvas adapter. Rust layout checks passed in both styles with Latin Modern Math. The signed APK contains x86, x86_64, and ARM64 libraries built with the NDK at API 26. The API 30 x86 emulator displayed the Tier 1 samples, including the two sum styles, and exposed the unsupported binomial fallback and its LaTeX source in the UI hierarchy. API 26, ARM64 device rendering, TalkBack, large text, and tablet behavior remain unverified on the Tier 1 revision.

The learner store uses transactions with ordinary inserts and updates for package registration, notes, and drafts. Android API 26 supplies SQLite 3.18, and UPSERT first appeared in SQLite 3.24. Import, validation, and restore path parsing now use `Paths.get`. The offline JVM `test ktlintCheck` gate passed, including regression checks for preserving note creation time and rolling back a stale draft command. [Android SQLite versions](https://developer.android.com/reference/android/database/sqlite/package-summary.html) · [SQLite UPSERT history](https://www.sqlite.org/lang_upsert.html#history)

An initial API 30 x86 probe ran the schema and representative write statements through framework SQLite 3.28.0. It verified migration and rollback within a single instrumentation process.

The [Android SQLite driver](android-storage/README.md) now runs the shared learner store, schema, migrations, and backup codec through framework SQLite. The signed storage probe passed on the API 30 x86 emulator. It committed a draft, terminated its own process after writing a second draft inside an open transaction, and verified recovery in a new process after an explicit force-stop. It checked durable command replay and retry, actual foreign-key rejection, schema-1 record migration, and backup restore on Android. JDBC restored the Android-produced backup, exported identical bytes, and replayed the saved command. The offline JVM tests and Kotlin lint passed with the Android storage module enabled.

That probe did not exercise the study application. See the app-level checks below.

### Android study application

The [study app and reproducible demo](android-app/README.md) build a signed debug APK with a declared API 26 minimum and x86, x86_64, and ARM64 renderers. The app uses the shared package reader, engine, framework SQLite driver and Texlet Canvas adapter. It provides import preview, revision selection, branching study, proof autosave and self-review, study history and notes, due reviews, and document-picker backup and restore. There is no account setup, internet permission, WebView, remote asset loading, or automatic Android backup. The app uses framework views in the vertical slice under [ADR-013](docs/markdown/decisions.md#adr-013--framework-views-and-texlet-for-the-android-vertical-slice).

**Verified on a Pixel 3a API 30 x86 emulator:** The signed APK installed and opened in airplane mode with Wi-Fi off. Initial checks used a temporary instrumentation harness after injected taps on picker rows did not select files. A follow-up run used keyboard focus to select the original package in the system picker and reinstalled it through the app. The application presented the course preview, installed the validated archive, accepted an exact-choice submission, displayed native formula previews alongside selectable source, and saved a proof draft. An explicit `am force-stop` removed the running process. A cold launch in a new process reopened the same session and displayed the committed draft and saved indicator. This covers the force-stop and reopen portion of D01.

In a separate run, an incorrect first choice followed by a correct retry still entered remediation (L01). Skipping the proof allowed the unit to finish (L03). Removing the original demo revision from the active library preserved the saved note and readable attempt history (D03).

Using the same offline setup, the test installed version 0.2.1 and reopened a version 0.2.0 session with its original draft (D02). It opened a hint, compared the proof, marked a rubric item, and finished the unit with the proof labeled unassessed self-review. Advancing the emulator to the next local date exposed the polynomial quotient check under **Due review**. The learner submitted the fresh answer successfully and the emulator clock was restored (R01). The installed build exported JSON through the system picker. Backup restore ran in both a separate empty app sandbox and the populated sandbox. The follow-up repeated both restores through normal picker selection while airplane mode was on and Wi-Fi was off. The empty restore reported both missing revisions, displayed the earlier proof in history, and let the learner resume the pinned fresh check after reimport. The populated restore refused the file (B01 and the nonempty-profile portion of B02).

**Automated checks:** JVM tests and Kotlin lint passed with both Android modules enabled. The focused shared checks cover review entry validation and durable submission order across command replay. The Rust workspace and Tier 1 font test passed. The handbook build and link/content checks passed.

**Known limits:** Full Markdown formatting, localizable Android strings, and proof reminder controls are pending. The app displays course Markdown as readable source with supplemental native formula previews. Large record reads near Android cursor-window limits are still unverified.

**Display checks on the API 30 emulator:** At 320 dp and 2.0 font scale, Tab focused **Continue learning** and the learner could reach the question, formula previews, remediation and proof save action by scrolling. At an 800 dp emulated width, the proof editor and save actions remained available. This checks both layout widths on the phone emulator. It provides partial A01 evidence.

**Exit gate:** M3 remains open. The API 30 emulator covered the core offline flows, including system document selection by keyboard. Full TalkBack and keyboard traversal, API 26, and representative ARM64 and tablet device checks remain open.
