# The Axiora handbook

Axiora is a proposed offline learning environment for proof-based mathematics. It pairs a native Android study application with a separate content toolchain. This handbook develops the idea into a bounded, implementable design.

**Status: design baseline 0.1 · 16 November 2026.** Requirements below are intended contracts, not claims about existing software. Implemented parts include this documentation site and its checks, an illustrative package fixture, the Kotlin content model and validator, a deterministic package CLI, and the headless study/review engine. The JVM SQLite repository, package installer, migration, and backup/restore adapter also pass Phase 4 acceptance checks. Android has not started; see the [verified implementation record](../../IMPLEMENTATION_STATUS.md).

## Start with the learning problem

A learner can recite a definition and still fail to recognize its examples. They can follow a proof and still be unable to start one. Axiora should make these differences visible and provide useful next steps. It should gradually reduce assistance as the learner becomes more capable.

The first release will test that premise with one carefully authored course slice. Broad curriculum coverage, arbitrary textbook conversion, and a conversational tutor come later, if the small version proves useful.

## Reading paths

| Your question | Read |
| --- | --- |
| What are we making, for whom, and what counts as success? | [Product and scope](product.md) |
| How should an actual study session teach? | [Learning and assessment](pedagogy.md) |
| How are the components separated? | [System architecture](architecture.md) |
| What must a package and teaching unit contain? | [Content contract](content.md) |
| How are attempts, progress, and reviews preserved? | [Learner state](learner-state.md) |
| How does source material become usable content? | [Authoring and compilation](compiler.md) |
| What does the Android experience feel like? | [Study experience](experience.md) |
| What should be implemented first, and how is it accepted? | [Delivery and verification](delivery.md) |
| Why were these choices made? | [Decision log](decisions.md) |

For AI-assisted implementation, read the [repository instructions](../../AGENTS.md), then the relevant contracts. The [example package](../../examples/prime-and-maximal/README.md) makes the content model concrete.

## Four things that must remain separate

| Entity | Meaning | Example |
| --- | --- | --- |
| Concept | Mathematical knowledge, under stated conventions | Prime ideal in a commutative ring with identity |
| Learning unit | A bounded teaching experience | Find why prime does not imply maximal |
| Package | A versioned distribution of content | A course slice containing several units |
| Learner record | A person's work and observations over time | An attempted proof, hints used, and later review |

A concept can appear in many units. A unit can teach several concepts. Packages assemble units. Learner records refer to stable content identifiers without becoming part of the package.

## Design commitments

- The core study loop works offline, without login or runtime AI.
- Content is declarative data with explicit limits and compatibility rules.
- Structured answers can be checked only within a declared evaluator's domain.
- Open proofs use rubrics and learner review in the first release.
- Completion, correctness, verification, and durable learning are separate signals.
- Content updates retain historical work and never silently reinterpret old evidence.

## How to read normative language

**Must** describes a required invariant or acceptance condition. **Should** describes a default that may be changed with a recorded reason. **May** describes an optional capability. “Deferred” means outside the first release. Numerical limits and review intervals are baseline product policies to evaluate, not empirically established optimal values.

The detailed Markdown is authoritative. HTML is generated from those same files. Changing a contract should update its example, linked decisions, and acceptance criteria in the same change.
