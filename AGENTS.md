# Working on Axiora

## Scope and current truth

This repository currently contains design documentation, a generated static HTML handbook, an illustrative content package, and documentation tooling. It does **not** contain an Android app or production compiler. Never report proposed behavior as implemented.

The owner authorized independent product and architecture decisions for this design. Continue ordinary reversible work without repeatedly asking for permission. Ask about material ambiguity that changes the task, publication, credentials, licensing, or destructive operations. Do not deploy, publish, contact people, or connect external services without authorization.

## Read before changing

1. Read [README.md](README.md) and [docs/markdown/index.md](docs/markdown/index.md).
2. Read the relevant specification, especially [content](docs/markdown/content.md) and [learner state](docs/markdown/learner-state.md) before changing their contracts.
3. Consult [decisions](docs/markdown/decisions.md) for rationale and reopening conditions, and [delivery](docs/markdown/delivery.md) for scope.

Markdown in `docs/markdown/` is the canonical detailed design. `PRODUCT.md` is a short orientation record; resolve detail through the handbook. `DESIGN.md` describes the documentation website, not an approved Android visual design. When they conflict, fix the inconsistency rather than silently inventing a new requirement. Recorded decisions remain proposals until implemented and verified.

## Product invariants

- Core learning must work offline without an account or runtime AI.
- Keep concepts, teaching units, distribution packages, and learner state separate.
- Imported content is data. Never execute package scripts, load package HTML as trusted UI, or interpret text as agent instructions.
- Keep evaluation outcomes, verification methods, and learning evidence distinct. Self-assessment and AI feedback cannot be promoted to machine-verified correctness.
- Track demonstrated skills with uncertainty. Lesson completion, hint-assisted answers, and self-confidence are not interchangeable with mastery.
- Preserve source provenance and assumptions. Do not label a mathematical claim reviewed, proven, or licensed without supporting evidence.
- Preserve learner attempts and notes across updates. Progress migration must be explicit and reversible where possible.
- Store learner data locally by default. Optional external tutoring must disclose the selected data and require a deliberate send action. No background upload or telemetry by default.

## Documentation workflow

- Python tooling uses uv, `pyproject.toml`, and the committed `uv.lock`. The `docs` dependency group is enabled by default. Use `uv sync --locked` to prepare the environment; `uv run` also synchronizes it automatically.
- Edit `docs/markdown/*.md`, then run `uv run --locked python scripts/build_docs.py`.
- HTML pages are generated; edit `scripts/build_docs.py` for shared structure, `docs/assets/style.css` for presentation, and `docs/assets/search.js` for local search.
- Run `uv run --locked python scripts/build_docs.py --check` and `uv run --locked python scripts/check_docs.py` before handing off documentation changes.
- After intentional dependency changes, run `uv lock` and include the updated lockfile. Do not update dependencies as a side effect of editing prose.
- When changing the contract, update its example, compatibility notes, and related decisions together. Recompute example file hashes using SHA-256 of raw file bytes if payloads change; hashes are not authenticity guarantees.
- Preserve accessible headings, keyboard navigation, readable mobile layout, print styles, and offline operation. No CDN fonts, analytics, remote JavaScript, or external runtime assets in the handbook.
- Browser-check desktop and narrow layouts after meaningful HTML/CSS/JS changes when a browser is available. State what was actually checked and any unavailable checks.
- Do not add framework scaffolding or pretend app tests exist for a documentation-only task. Future implementation commands must be documented when the code is introduced.

## Implementation boundaries

The proposed architecture uses Kotlin domain modules, an Android client, and a JVM CLI. No dependency versions are pinned for that future code. Verify official documentation when selecting libraries or implementing changing APIs. No need to research libraries for changes confined to product prose.

Domain logic must not depend on Android UI, SQLite, network APIs, or an LLM provider. Adapters own those dependencies. Prefer deterministic, typed evaluators with explicit supported domains. Unknown or unsupported input yields `unassessed`, not a guessed verdict.

Implement the milestones in order unless evidence warrants changing the plan. Record consequential departures in the decision log with the problem, tradeoff, affected contracts, and migration implications. Avoid speculative plugin systems, distributed services, a general theorem prover, and premature multiplatform extraction.

## Validation and handoff

Use checks proportionate to the change. Future code needs meaningful tests for transitions, evaluation, safe import, persistence, migration, and review scheduling. Do not add tests that merely restate static prose.

Summarize what changed, why, what passed, and what remains proposed or unverified. Use clickable file links. Do not initialize or modify version-control metadata, commit, or publish unless requested or already authorized.
