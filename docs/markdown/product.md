# Product and scope

## The product promise

Help an independent learner build mathematical understanding and proof ability through structured work they can resume anywhere. Axiora should make the next useful task clear while leaving room to explore, write, and think for an extended period.

Its distinctive mechanism is the combination of reusable teaching units, explicit evidence about different skills, and an offline study engine. AI is an optional production or assistance tool rather than the organizing principle of the learner experience.

## Primary learner

The initial design assumes an adult studying undergraduate or early graduate proof-based mathematics. They know basic mathematical notation, have limited access to a tutor, and use an Android phone or tablet. Their connectivity may be intermittent. They may alternate between a textbook, paper, and the app.

This is a design hypothesis to test with real learners. Axiora has no validated user research or demonstrated learning outcomes yet.

The initial course assumes comfort with elementary ring theory. Prerequisite diagnostics should reveal gaps in ideals, quotient rings, integral domains, and fields. This is not a course that teaches all of those topics from zero.

## Jobs to support

| Situation | Learner's job | Useful outcome |
| --- | --- | --- |
| Opening an unfamiliar topic | Discover what matters and what is assumed | A clear objective and a useful first question |
| Confusing two definitions | Explore examples and boundary cases | State the distinction and construct an example |
| Unable to start a proof | Plan an approach with limited support | Produce an attempt before seeing a solution |
| Returning after a gap | Recover prior knowledge | Retrieve an idea and apply it again |
| Studying without connectivity | Continue the same work | No blocked lesson, missing formula, or lost attempt |

## A complete first release

The first release contains a single manually reviewed course slice: **prime and maximal ideals**. It includes a short prerequisite check, examples and nonexamples, a counterexample to a false implication, a guided proof of the valid implication, an independent attempt, and delayed review.

The committed example is a smaller contract fixture inside that slice. It does not by itself constitute the full release curriculum.

Required capabilities:

- Import a validated package from device storage and inspect its identity, conventions, prerequisites, and review status.
- Resume a deterministic branching unit with exact-choice evaluation.
- Write a plain-text proof with LaTeX notation, save it locally, and compare it with a model solution and rubric.
- Record hints and assistance without pretending the resulting work was independent.
- Schedule simple reviews and allow manual topic selection.
- Preserve notes, attempts, and historical results across an application restart and compatible content update.
- Export and restore learner records through an explicit local file action.

The first release uses a small vocabulary of activities. Rich symbolic equivalence checking and automatic free-text grading are not prerequisites.

## Out of scope initially

No cloud accounts, synchronization, content marketplace, teacher dashboard, leaderboards, streak economy, handwriting recognition, collaborative editing, arbitrary code execution, formal theorem proving, or real-time conversational tutor. No promise to convert an arbitrary PDF into a correct course automatically.

There is no commitment to support every branch of mathematics with the initial evaluator set. A general content model must not become a reason to build a general computer algebra system.

## What success means

Evaluate three different things:

| Dimension | Evidence to collect | What it cannot establish alone |
| --- | --- | --- |
| Usability | Learners import, resume, request a hint, and recover saved work | Mathematical learning |
| Reasoning | A human reviewer compares independent attempts against a stated rubric | Long-term retention |
| Retention and transfer | Learners solve delayed, previously unseen tasks with reduced help | Effectiveness across all topics or populations |

Pilot design: recruit a small number of willing learners, ask them to complete a session and return after a delay, and inspect where their reasoning and the interface break down. Sample size, consent wording, and any research design remain open. Do not publish efficacy claims from anecdotal completion rates.

The first pilot's product gate is practical: can learners complete and resume the loop, understand the assessment labels, and produce enough work for meaningful human feedback? A later controlled evaluation is needed for causal learning claims.

## Operating principles

**Make effort meaningful.** A substantial proof attempt can be a better session than many fast answers. Offer short and extended sessions without penalizing either.

**Make uncertainty visible.** Show “compared with rubric” or “answer checked” rather than a universal “verified” badge.

**Make recovery ordinary.** Missing prerequisites, corrupt imports, incompatible updates, and unfinished arguments are expected states with useful next actions.

**Keep autonomy.** Recommendations explain their reason and can be overridden. The learner may inspect definitions, revisit work, or stop a session.

## Open product questions

Tablet versus phone priority, target Android devices, additional languages, distribution model, software license, content licensing, and final name availability remain owner decisions. None blocks documenting or prototyping the offline loop.
