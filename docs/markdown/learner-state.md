# Learner state and review

## Preserve the work before summarizing it

Proof drafts, submitted attempts, opened hints, and learner notes are primary records. Progress indicators and recommendations are derived views. If a summary is wrong or a policy changes, it should be possible to rebuild it without losing the learner's actual work.

Learner data never belongs inside an installed content package. Removing a course from the active library must not silently delete its attempts or notes.

## Proposed local records

| Record | Important fields | Lifetime |
| --- | --- | --- |
| `package_revision` | Package ID, version, manifest hash, storage location, import time, active-library flag | Immutable installed identity |
| `session` | ID, exact package/unit revision, active activity, first-result branch, state, checkpoint | Mutable checkpoint |
| `draft` | Session, activity, text, revision counter, modified time | Mutable, retained until explicit removal |
| `attempt` | ID, command ID, activity, sequence, response, outcome, method, assistance snapshot, readable context snapshot | Immutable submission |
| `learning_event` | Monotonic database sequence, event ID, type, payload version, occurrence time | Append-only explanation history |
| `observation` | Attempt, objective, skill, family, evidence-policy version | Immutable evidence input |
| `objective_summary` | Objective and unit revision, descriptive state, supporting attempts, policy version | Rebuildable projection |
| `review_task` | Objective and unit revision, due date, interval stage, status | Rebuildable recommendation |
| `note` | Stable content anchor, optional exact revision, text, timestamps | Learner-owned editable work |

An `attempt` must retain enough of the assessed prompt, selected option, evaluator key revision, and rubric context to interpret the result if the original package is removed. Full old package revisions should remain while referenced, but a bounded prompt snapshot provides a second layer of historical readability.

Do not store one mutable “score” row as the only record of a person's learning.

## Session state machine

```text
not_started -> active -> completed
                   |
                   +-> paused -> active
                   |
                   +-> abandoned
```

An active activity has type-specific substates. For a choice: awaiting selection, ready to submit, feedback visible, ready to continue. For a proof: drafting, comparing, reflecting, ready to continue. The first submission fixes a choice branch even when later retries succeed.

Pause records a checkpoint. Restart creates a new session. Back navigation exposes snapshots without moving the durable checkpoint. Completed sessions stay completed; revisiting creates new work.

## Atomicity and duplicate commands

Each user command has a generated unique ID. A transaction writes its attempt or event, updates the session checkpoint, and updates or invalidates projections together. A uniqueness constraint on command ID prevents double taps, process retries, or lifecycle restoration from producing duplicate learning events.

Draft saving uses a monotonically increasing revision counter, not wall-clock last-write-wins. An older autosave cannot overwrite a newer draft. Display an unsaved indicator until the transaction commits. On a storage failure, keep the current text on screen and offer retry or explicit copying/export; do not advance and hope it was saved.

Android lifecycle termination can happen without a final callback. Save after edits with a short debounce and on explicit actions; do not rely solely on a pause callback. Exact debounce and retention limits are implementation tuning parameters.

## Events and projections

Initial event types are `session_started`, `choice_submitted`, `hint_revealed`, `solution_revealed`, `proof_submitted`, `rubric_recorded`, `activity_skipped`, `reflection_recorded`, and `session_completed`. Draft edits are stored as drafts rather than a keystroke event stream.

Every event includes its session and content revision, a payload schema version, and the database sequence. Timestamps support review scheduling; sequence establishes order when clocks disagree. Domain commands emit events only after valid state transitions.

The app may recompute summaries after a policy upgrade. It must retain previous attempt outcomes, provenance, and assessment methods. An AI opinion added later cannot overwrite a historical self-review or exact-choice result.

## Initial review scheduler

Use a transparent interval ladder: **1, 3, 7, 14, and 30 days**. This is an initial product heuristic, not an optimal learning claim. A task records its stage and policy version.

- The first eligible, unassisted, first-submission exact-choice success creates a review for the next local calendar day.
- At a due review, an eligible success from a different assessment family advances one stage and schedules from the completion date. Practice before the due date does not advance the ladder.
- An eligible first-submission error resets the stage and schedules the next day.
- A hinted answer, retry, skipped activity, technical failure, or solution-assisted response does not advance the ladder. Offer practice or keep the task pending without creating an immediate repetition loop.
- A self-reviewed proof offers a learner-chosen reminder, defaulting to the next day. It never automatically advances a checked-skill ladder.
- At the last stage, successful due reviews remain on a 30-day interval; no content is declared permanently learned.

Deduplicate tasks by objective, exact unit revision, and policy version. A unit with no fresh assessment family can still be practiced, but the app must explain that no new independent check is available. The first course needs at least two families per checked objective to avoid this dead end.

Use the device's local date for presentation and due-day calculations. Keep UTC timestamps and the recorded zone offset for history. If the clock moves backwards, preserve sequence order and postpone automatic evidence promotion until timestamps are coherent. A large forward jump may make tasks due, but must not produce automatic success or a storm of duplicate tasks.

The `demonstrated` rule additionally requires at least 24 elapsed hours and distinct local study dates, as defined in [assessment policy](pedagogy.md). Due-day scheduling and evidence eligibility are intentionally separate.

## Recommendation policy

Offer at most three clear actions: resume unfinished work, review a due objective, or study the next available unit. Explain each recommendation in plain language: “You used a hint last time” or “This idea is due for review.”

The learner can choose another topic. Prerequisite gaps create a visible suggestion, not an absolute navigation lock. Course authors may suggest an order but cannot require the app to conceal definitions until a score threshold is reached.

## Updating and removing content

Active sessions pin exact revisions. Installing a newer course revision affects new sessions only. Offer “Continue existing work” and “Start updated unit,” showing that historical observations remain with the old revision. A derived summary is never silently carried across unit versions in 0.1.

Uninstalling content makes it unavailable for new sessions. Historical attempts and notes remain readable through their snapshots, and old revisions needed by active work remain retained. Explicit learner-data deletion is a separate action showing the scope. Garbage collection must not erase referenced revisions or orphan learner work.

## Backup and restore

Version 0.1 should export a user-selected archive containing a versioned JSON learner snapshot and retained content references. It excludes textbook files, credentials, transient caches, and optional AI secrets. Include the database schema/export version, all draft and attempt IDs, and package hashes. Show that proof text and notes are personal data before export.

Use an application-level serialization, not a raw live SQLite copy. Take a consistent snapshot in a transaction. Backup encryption is deferred; clearly label the file as readable personal data and let the user choose its destination. Do not upload it automatically.

For the first release, restore is permitted only into an empty learner profile. Validate limits, versions, referential integrity, and IDs in staging; commit atomically. Reject a nonempty-profile restore with a clear explanation rather than attempting an unspecified merge. Missing packages do not invalidate readable learner history; mark the content unavailable and offer reimport by hash. Cross-device synchronization and merge conflict resolution are deferred.

## Phase 4 JVM persistence format

The implemented adapter uses SQLite schema 2 and application backup format 2. These versions are independent of content schema 0.1. A backup is one UTF-8 JSON document with exactly `format`, `version`, and `tables`; it is not a live database copy or a package archive. The format identifier is `axiora-learner-backup`. Tables contain exact record columns, BLOBs use base64 strings, and numeric fields use integers. The adapter bounds input to 64 MiB, 250,000 records per table, and 2,000,000 characters per text field. Duplicate keys, trailing JSON, unknown formats/tables/columns, invalid field types, and relational constraint failures are rejected before commit.

For example, a format-2 package record adds `"active": 0` when removed from the active library; an attempt adds `"context_snapshot": "Prompt: …; selected option: …; key revision: …; rubric: …"`. `StudyRepository` requires the application to supply bounded readable context from the pinned package, because the domain model contains identifiers rather than teaching prose. `attemptHistory` reads the response, method, assistance, and context without loading package files. Resume still requires the exact pinned unit through `UnitLookup`.

Schema 1 migrates transactionally to schema 2 without rewriting primary records. Existing packages start active. Older attempts receive the explicit text `Context unavailable in schema 1`; migration cannot reconstruct a prompt that was never saved. Format-1 backups remain accepted with the same defaults. Format 2 is refused by the older format-1 reader. The [saved schema-1 fixture](../../storage/src/test/resources/schema-v1.sql) includes attempts, drafts, events, observations, summaries, review tasks, notes, and a durable command receipt for compatibility checks.

Library removal sets the active flag and blocks new sessions, retaining archives, existing sessions, attempts, and notes. Reconciliation preserves that flag; an explicit successful reimport reactivates the revision. No automatic content garbage collection or learner-data deletion is implemented. Recovery deletes abandoned import staging files and registers validated promoted archives left by a terminated process; a failed registration keeps those bytes for retry. Run reconciliation at startup before accepting imports, using one installer at a time. The Android vertical slice calls reconciliation when opening the learner workspace.

Restore validates and inserts records in one transaction into an empty profile and returns references whose archive cannot be validated against the recorded identity and hash. It preserves the original content references, command receipts, and personal work even when all packages are missing. Package bytes are excluded; the application must reimport unavailable packages and resolve exact revisions before resuming. The Android vertical slice exposes backup and restore through the system file picker and shows the save state in the proof workspace.

## Privacy defaults

No account, background network calls, analytics collection, advertising identifiers, or automatic crash uploads are needed. Local diagnostics should contain codes and content IDs, not proof text or textbook passages. A support export should preview its contents and omit personal work unless selected.

Future AI tutoring must require an explicit per-request send action showing the chosen prompt and relevant excerpts. That action cannot grant indefinite access to the notebook or background upload permission.
