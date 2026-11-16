# Implementation status

This record tracks verified work against [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Design contracts in `docs/markdown/` remain authoritative. Phase 1 is complete. Phase 2 is ready to begin; M1 remains open until its package and reader gate passes.

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
