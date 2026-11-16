# Implementation status

This record tracks verified work against [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Design contracts in `docs/markdown/` remain authoritative. Phases 1 and 2 are complete; M1 is closed.

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

**Remaining in Phase 1:** none. Phase 2 archive creation and hostile archive reading, mathematical and teaching review, Android, storage, and publication remain future work.

## Phase 2 — complete

**Implemented:** `pack` validates the source, serializes JSON with sorted object keys, sorts archive paths, hashes final payload bytes, and writes a fixed-time ZIP STORE archive plus an adjacent build report. Existing output requires `--overwrite`; output must be outside the authoring directory. `inspect` checks bounded archive size, entry count, UTF-8 names, central and local ZIP metadata, path and case collisions, regular-file metadata, stored-entry size limits, and streamed expansion limits. It stages temporarily, checks UTF-8 JSON and nesting limits, and runs the shared Phase 1 validator before returning a revision descriptor. Installation is not included.

**Verified:** `GRADLE_USER_HOME=/tmp/axiora-gradle ./gradlew --no-daemon test :compiler-cli:installDist ktlintCheck` — passed. CLI `validate → pack → inspect` passed for the illustrative fixture. Two unchanged builds compared byte-identical with `cmp`; inspect reported package `axiora.ca.first-slice@0.1.0`, four payloads, and 11,353 uncompressed bytes. CLI integration tests cover pack and inspect. Hostile reader tests reject traversal, symlink metadata, duplicate filenames and JSON keys, case collisions, encryption, malformed UTF-8 names and payloads, central/local size mismatches, CRC-corrupt payloads, unknown capabilities, corrupt hashes, undeclared files, compressed expansion, and oversized archives. Tests verify the packer leaves the source manifest unchanged.

**Handoff:** deterministic `.axp` artifacts, external build reports with source hashes and diagnostics, a bounded shared reader, revision descriptors, CLI integration, and hostile archive tests. Installation remains Phase 4 work. Hashes establish byte integrity only; they do not authenticate authorship or review claims. Mathematical and teaching review remain outstanding.
