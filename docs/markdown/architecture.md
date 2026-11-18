# System architecture

## Boundaries before services

Axiora should begin as two local programs sharing domain contracts: an Android study application and a command-line content compiler. No backend is required for the first release.

```text
Author materials + human-authored units
                 |
        Authoring / compiler CLI
        validate -> normalize -> package
                 |
         Immutable .axp archive
                 |
       Android import boundary
       inspect -> validate -> stage -> commit
                 |
           Study application
       content reader + domain engine
                 |
       Local learner database
       drafts, attempts, events, reviews
```

Optional future AI adapters sit outside these core boundaries. They may propose content or feedback; they do not gain authority to mutate progress or install packages.

## Proposed module structure

The content, validation, compiler, learning-core, and storage modules serve the Android vertical slice. The [implementation status](../../IMPLEMENTATION_STATUS.md) records device coverage.

| Module | Responsibility | Must not depend on |
| --- | --- | --- |
| `content-model` | Typed identifiers, unit and concept structures, version parsing | Android, database, UI, network |
| `content-validation` | Structural and semantic package rules, diagnostics | Android UI, AI provider |
| `learning-core` | Session transitions, evaluation, evidence and review policy | Android, file system, SQLite, network |
| `storage` | Transactions, persistence, migrations, content lookup | UI components; implemented initially as a JVM SQLite JDBC adapter |
| `android-app` | Accessible screens, lifecycle, file selection, rendering | Compiler generation internals |
| `compiler-cli` | Authoring input, normalization, validation, reproducible packaging | Android |

Initially use Kotlin/JVM libraries, with Android-compatible dependencies for shared modules. Do not begin with Kotlin Multiplatform, a web client, or a distributed service architecture. Revisit extraction after a second supported client exists.

## Core application flow

The UI sends typed commands such as `SubmitChoice`, `SaveDraft`, `RevealHint`, `RevealSolution`, `RecordReflection`, and `Advance`. The domain engine returns a new session state plus facts to persist. Storage commits them in one transaction before the UI presents the next durable state.

The engine receives its clock, identifier generator, content lookup, and review policy through interfaces. A repeated command ID must return the original result without creating duplicate attempts or moving twice. Unsupported commands in a state produce a recoverable domain error rather than silently skipping content.

Explanations do not imply submission. A choice must be explicitly submitted. A proof draft is saved frequently but does not become an assessment merely because the app closes.

## Content and learner storage

Store immutable imported package revisions in app-private storage. Keep a local SQLite database for package registry, session checkpoints, proof drafts, attempts, observations, notes, and review tasks. Use the Android Storage Access Framework for user-selected import, export, and restore files.

The database stores references to exact package and unit revisions. Old revisions stay available while active sessions or historical attempts refer to them. Content deduplication is optional; correctness and recovery take priority over saving a few files.

Do not build a full event-sourced application. Use ordinary relational tables for current state with an append-only learning event history sufficient to explain observations and rebuild derived summaries. Session checkpoints and event rows are committed together. See [learner state](learner-state.md).

## Import transaction

1. Copy the selected archive into a bounded staging area; do not trust the file extension.
2. Inspect the archive before extraction: reject unsafe paths, links, duplicate names, excessive sizes, and unsupported entries.
3. Read the manifest; check schema version and required capabilities.
4. Verify inventory, raw-byte hashes, JSON structure, identifiers, graph references, and local dependencies.
5. Show package identity, review claims, missing prerequisites, and any update consequences.
6. On the learner's import action, atomically register the complete revision and make it available.
7. On failure, retain the previous installed revision and remove only the new staging artifacts.

No partial course may appear as installed. If a crash occurs between filesystem promotion and database registration, startup reconciliation must either complete the validated registration or remove the unregistered staged revision. It must never attach an incomplete payload to an installed record.

## Trust boundaries

| Input | Trust level | Required treatment |
| --- | --- | --- |
| Package text, equations, names, and metadata | Untrusted authored data | Escape text, restrict Markdown and math commands, enforce limits |
| Archive file paths | Untrusted file operations | Normalize and reject traversal, links, collisions, and unlisted payloads |
| Mathematical answer key | Author claim | Record provenance and review state; exact evaluation cannot validate the theorem |
| Learner text | Personal local data | Preserve drafts, avoid logs containing content, upload only by explicit action |
| Optional AI response | Unverified suggestion | Display source and limitations, never apply hidden grading or state mutations |

Hashes detect corruption and bind a manifest to its payload. They do not prove publisher identity: anyone can replace both. Cryptographic publisher signatures and a trust registry are deferred. The first app must label review status as a package author's claim rather than an authenticated endorsement.

## Mathematical rendering

Use a renderer adapter, isolated from domain semantics. Mathematical source is restricted LaTeX stored as text. The initial supported subset must be established during a rendering spike; unsupported commands must fail author validation or render a readable source fallback with a diagnostic.

The Android renderer may use a tightly restricted local WebView if the spike shows that a native renderer cannot meet notation needs. This is an implementation option, not a decision to trust package HTML. Bundle rendering resources in the app, disable remote requests and file navigation, sanitize content, and expose no JavaScript bridge to package data. Generated HTML must not be able to launch arbitrary intents or reach the network.

## Failure semantics

| Failure | Result |
| --- | --- |
| Evaluator unavailable | Keep the response and mark it unassessed; do not guess |
| Incompatible package schema | Refuse installation; explain the required reader version |
| Missing learning prerequisite | Allow browsing, explain the gap, offer a remedial path if installed |
| Invalid internal reference | Reject the whole package revision |
| Persistence failure | Keep the current visible response; do not claim it was saved or advance durably |
| Renderer failure | Offer source text and a reportable diagnostic; preserve the session |
| Clock moves backwards | Retain event order; prevent automatic promotion and duplicate review tasks |

## Consequences of this architecture

The engine remains testable without Android, while the product gets native navigation, local storage, and lifecycle behavior. A JVM compiler reuses the package validator and reduces contract drift. The cost is maintaining a formal content boundary early. That cost is worthwhile because content imports and progress compatibility are harder to retrofit than a new screen.
