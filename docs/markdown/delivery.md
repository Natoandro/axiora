# Delivery and verification

## Build one complete learning loop

Milestones are ordered by risk and evidence, not estimated calendar dates. There is no implementation team, budget, or delivery date established. M0, M1, and M2 are complete; durable storage and Android work have not started. See the [verified implementation status](../../IMPLEMENTATION_STATUS.md).

| Milestone | Deliverable | Exit condition |
| --- | --- | --- |
| M0 — Design baseline | Handbook, repository guidance, example fixture, documentation checks | Human and AI editions agree; example follows its documented subset |
| M1 — Executable content contract | Kotlin types, strict schema, shared validator, deterministic packager | Valid fixture packages; invalid and hostile inputs fail with useful diagnostics |
| M2 — Headless study loop | Domain engine, command processing, attempts, evidence and review policy | A complete branching unit runs deterministically without Android or network |
| M3 — Android vertical slice | Import, lesson, proof editor, local persistence, backup/restore | A learner completes, closes, resumes, reviews, and restores work offline |
| M4 — Reviewed course and pilot | Complete course slice with fresh assessment families, reviewed mathematics, usability sessions | Findings identify learning and interface weaknesses; blocking data-loss and assessment issues resolved |
| M5 — Authoring assistance | Incremental AI draft generation and provenance, only if justified | Drafts are reviewable and validated; no automatic mathematical certification |

Optional conversational tutoring follows evidence of need. It is not a gate for M1–M4.

For actionable tasks and phase gates, follow the [phased implementation plan](../../IMPLEMENTATION_PLAN.md). It expands these milestones without changing their contracts.

## First implementation task

Start M1 with the current JSON fixture and strict models for five activity types. Implement structural and semantic validation and a CLI that reports errors. Add hostile archive tests before building a general authoring pipeline. Then use that same validator behind Android import.

The fixture is deliberately small. Expand it into three units before freezing the contract: prerequisite recognition, the distinction and counterexample, and proof construction plus a later fresh check. Do not infer a full course from one successful fixture.

## Acceptance scenarios

| ID | Scenario | Required observable result |
| --- | --- | --- |
| C01 | Import the same package twice | One immutable revision, no duplicated progress |
| C02 | Import a different payload with the same ID/version | Clear conflict; installed revision preserved |
| C03 | Encounter traversal, symlink, duplicate filename/key, oversized expansion, or unknown capability | Reject before unsafe extraction or partial installation |
| L01 | Submit a wrong choice, retry correctly, then continue | Both attempts retained; first-result remediation branch preserved |
| L02 | Tap submit twice or replay the same command after restart | One durable submission and one transition |
| L03 | Finish a unit by skipping a proof | Completion recorded, no fabricated proof correctness |
| P01 | Reveal a hint or solution before answering | Assistance retained; no independent evidence credit |
| P02 | Self-review a proof with all rubric items marked met | Method remains self-review; no machine-checked proof claim |
| D01 | Force-stop while writing, reopen, then revise | Last committed draft restored; saved/unsaved status truthful |
| D02 | Install a revised unit while an old session exists | Old session remains pinned; new work can use the new revision |
| D03 | Uninstall a course with notes and attempts | Historical work remains readable; explicit deletion is separate |
| R01 | Return for a fresh due check | Review stage changes only on eligible first-submission evidence |
| R02 | Change device clock or zone | No duplicate tasks, reordered history, or automatic success |
| B01 | Export and restore into an empty profile | Attempts, drafts, notes, and references retained; missing packages identified |
| B02 | Restore into a nonempty profile or import a corrupt backup | Refuse safely; current learner data unchanged |
| A01 | Use large fonts, TalkBack, and keyboard navigation | Core actions and mathematical source remain available |
| O01 | Enable airplane mode after installation | All core import, study, draft, review, and backup flows work |

## Test strategy when implementation begins

Use domain tests for graph traversal, retry semantics, evidence eligibility, review dates, clock anomalies, and command idempotence. Use property-based or generated cases for bounded graph validation and path normalization where they find meaningful edge cases.

Use importer integration tests with malformed archives, transactional crash points, hash mismatches, and incomplete filesystem/database promotion. Use real database migration tests with saved attempts and drafts from previous schema versions.

Use Android integration tests for import, force-stop recovery, content updates, storage errors, backup/restore, and navigation. Run formula rendering and accessibility checks on representative phones and tablets. UI screenshots cannot establish correctness of learning state.

Use deterministic build checks to confirm byte-identical archives for unchanged inputs. Mathematical review remains human work and should be recorded alongside test results rather than implied by a green CI run.

## Checks available in this repository

```sh
uv run --locked python scripts/build_docs.py --check
uv run --locked python scripts/check_docs.py
```

The first checks generated HTML freshness. The second verifies local links/fragments and a limited set of fixture invariants: payload hashes, identity, supported activity shapes, references, reachability, cycles, and evaluator/objective pairing. It does not implement the full import contract, archive inspection, a complete schema validator, Android execution, or mathematical proof checking.

## Risks and planned reductions

| Risk | Reduction before expansion |
| --- | --- |
| DSL expresses pages but cannot teach reasoning | Manually author different unit types before introducing syntax sugar |
| Proof feedback creates false confidence | Keep self-review explicit; use human-reviewed examples and rubrics |
| Formula rendering is inaccessible or fragile | Run a focused notation and assistive-technology spike before UI scale-up |
| Content updates corrupt learner history | Pin revisions and test updates with historical records |
| Course creation consumes more effort than expected | Measure authoring and review time per unit in the pilot |
| Evidence model rewards guessing or repeated exposure | Distinct task families, first-submission policy, delayed independent checks |
| Large imported archives exhaust storage or memory | Bounded parsing, streaming limits, staged transactions, hostile fixtures |
| Optional AI expands privacy or correctness risk | Explicit send preview, adapter boundary, no authority over grades |

## Questions to settle at the right milestone

Before M1: exact Kotlin build layout, JSON schema tooling, and CLI distribution method. Before M3: Android minimum version, renderer, SQLite adapter, backup serialization details, and supported devices. Before external distribution: software license, course rights, name availability, and any publisher verification model.

These are real open decisions. They do not justify delaying the domain contract or creating a cloud service prematurely.
