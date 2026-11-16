# Learning and assessment

## Design a session around a capability

Each unit should name a small observable objective, such as “distinguish prime from maximal ideals using a counterexample.” Avoid objectives expressed only as exposure: “read the definition” is an activity, not evidence of understanding.

A useful session begins with a question, surfaces the learner's reasoning, offers an appropriate intervention, and ends with a fresh opportunity to act. The exact sequence depends on the mathematics. Do not impose one universal explanation–quiz template.

## A teaching sequence for the first slice

1. **Retrieve prerequisites.** Ask what makes a quotient a field or an integral domain. Offer a brief reminder when needed.
2. **Commit to a prediction.** Is every prime ideal maximal? Record the initial answer before revealing feedback.
3. **Investigate a boundary case.** Examine the zero ideal in the integers. Require a justification, not just another selection.
4. **Analyze the valid direction.** Explain why a maximal ideal is prime using quotient characterizations, under the stated ring conventions.
5. **Attempt a proof.** Let the learner plan and write before opening hints or the solution.
6. **Compare and revise.** Use a rubric to locate missing hypotheses, invalid inferences, and unexplained steps.
7. **Return later.** Present a different example or proof task to test retrieval and transfer.

The package fixture demonstrates steps 2–6 in miniature. It deliberately does not claim that one question and one self-reviewed proof establish mastery.

## Track distinct skills

| Skill key | Observable capability | Example evidence |
| --- | --- | --- |
| `recall` | Retrieve an accurate statement | State the definition without the reference open |
| `recognition` | Identify examples and nonexamples | Classify an ideal and justify the classification |
| `application` | Use a result under its hypotheses | Choose a quotient characterization appropriately |
| `proof_comprehension` | Explain an argument's structure | Identify why a field is an integral domain |
| `proof_construction` | Build a coherent argument | Prove maximal implies prime |
| `transfer` | Apply an idea in a new setting | Find a counterexample in an unfamiliar ring |

Self-explanation is a teaching activity across these skills, not an automatic scoring method. The first release may display skill-specific observations without calculating a single aggregate mastery percentage.

## Evaluation is not mastery

An attempt has independent dimensions:

- **Outcome:** `correct`, `incorrect`, `partial`, or `unassessed`.
- **Method:** `exact_choice`, `self_review`, `human_review`, `symbolic`, `formal`, or `ai_feedback`.
- **Assistance:** which hints, references, and solutions were opened, and when.
- **Evidence:** objective, skill, content revision, and assessment family.

Only `exact_choice` and `self_review` are required in the first release. Other methods are reserved for later contracts, not accepted first-release evaluator types.

An exact-choice evaluator checks agreement with the authored key. It does not establish that the learner can prove the claim or that the authored key is mathematically correct. Mathematical author review is a separate process.

An unsupported response must remain unassessed. A technical error, blank draft, or interrupted submission is not a mathematical error. An assessment may record a misconception as a hypothesis associated with an authored distractor; it must not infer a permanent learner diagnosis from one response.

## Proofs without automatic proof grading

The learner writes an attempt, then explicitly chooses “Compare with solution.” The app snapshots the attempt and reveals the model argument and rubric. Each criterion is marked `met`, `not_met`, or `unsure` by the learner. The resulting method is `self_review`, and the proof outcome remains `unassessed`.

Example rubric for maximal implies prime:

- State that the ideal is proper and the ambient ring is commutative with identity.
- Use that the quotient by a maximal ideal is a field.
- Explain that a field is an integral domain.
- Apply the quotient characterization of prime ideals and conclude the required implication.

Alternative valid direct proofs must be acknowledged in the rubric guidance. Matching the model solution's wording is never a criterion. Rubric uncertainty should lead to a reminder, a second attempt, or human feedback; it must not become a hidden failure score.

## Hints as support, not penalties

Hints should progress from a relevant definition to an intermediate goal to a structural outline. A complete solution is a separate reveal action. The app records each reveal but never shames or withholds essential content.

Assistance affects the interpretation of evidence. An answer after a revealed solution is useful practice but cannot qualify as an independent retrieval success. A later fresh, unassisted task can provide new evidence.

## Evidence policy for the first release

Use descriptive states per objective and skill:

- `unseen`: no eligible observation.
- `practicing`: observations exist, but independence or consistency is insufficient.
- `demonstrated`: at least two first-submission correct exact-choice observations from distinct assessment families, on distinct local study dates at least 24 hours apart, with no hints, reference reveals, or solutions shown before submission.
- `revisit`: a subsequent eligible first-submission error, or a passed review date, indicates more practice is useful.

Only skills covered by the evaluator can enter `demonstrated`. In version 0.1, exact-choice evidence is restricted to recall and recognition. Proof construction remains descriptively tracked through attempts and rubric reflections; it cannot enter `demonstrated` under this policy.

The 24-hour/two-family rule is an initial engineering heuristic, not a validated mastery model. Keep the policy version in projections so it can change without rewriting attempts. Do not treat a device clock as a trusted measure of elapsed learning time.

## Content quality rubric

Before a unit is included in a pilot, a mathematical reviewer should check conventions, hypotheses, examples, keys, model solutions, and rubric flexibility. A teaching reviewer should check whether the tasks elicit the stated skill, whether distractors are informative, and whether assistance actually helps.

A useful unit needs variation, a plausible misconception, an explanatory intervention, and a fresh check. The first release should prefer a few deeply reviewed units over broad generated coverage. No external empirical efficacy claims are assumed by this design.
