# Axiora content authoring (schema 0.1.0)

Axiora is an offline mathematics learning environment. Authors prepare concepts and lessons as course packages for learners to study in the Axiora app. This CLI helps create a draft package, edit its content, check its structure, and build or compare package archives. It runs locally without an account, network service, or AI.

Packages are authored as UTF-8 JSON files. Validation checks the content contract, not mathematical truth, licensing, or educational quality. The generated starter is a draft with placeholders. Plan the learning experience before editing the JSON.

## What an Axiora lesson consists of

An Axiora lesson is a learning unit: a bounded sequence of activities in which a learner practices an observable mathematical skill. The concept file defines reusable mathematical ideas and their assumptions. The unit file arranges an experience around those ideas. The manifest assembles units into a versioned package.

| Unit fields | What to author |
| --- | --- |
| `schema_version`, `id`, `version`, `title` | Stable identity, revision, and a title that tells the learner what they will work on. |
| `concept_ids`, `requires_concepts` | The concepts taught here and the prior knowledge the lesson assumes. |
| `objectives` | Observable performances tied to a taught concept and a skill. “Prove that the sum of two odd integers is even” gives the learner a concrete target. |
| `sources` | Provenance, locators, and rights declarations. Give each activity a `source_ids` list referring to its sources. |
| `entry`, `activities` | A starting activity ID and a map of activities keyed by ID. Every activity must be reachable, every path must end at an `end`, and branches move forward without cycles. |

| Activity | When to use it |
| --- | --- |
| `exposition` | Explain a definition, model a reasoning move, or revisit a prerequisite. Use `next` to continue. |
| `choice` | Ask for a prediction or a single keyed answer. Explain the reasoning in feedback and use the correct and incorrect transitions when different follow-up would help. |
| `proof` | Give learners room to draft an argument, request hints, and compare their work with a model and rubric. |
| `reflection` | Invite learners to explain a decision, identify a gap, or name a next step. The response and confidence are optional. |
| `end` | Summarize the reasoning and suggest what to revisit. Reaching this activity records completion. |

## How to structure a lesson

1. **Plan backward from the learner's work.** Write one focal objective, the prerequisite knowledge it assumes, and a task that would let a learner demonstrate it. Name the mathematical move you want to see. Recognizing which hypothesis applies and constructing a proof are different objectives and need different tasks.
2. **Start where the learner is.** If the needed ideas are familiar, ask learners to retrieve one or commit to a prediction before showing the explanation. If a key move is new, begin with a short worked example. Define notation where it first matters and keep each activity focused on one decision or idea.
3. **Make the mathematical distinction visible.** Pair an example with a carefully chosen nonexample or counterexample. Hold irrelevant features steady where possible and vary the property that decides the case. Ask which hypothesis, definition, or proof step accounts for the difference. Later, vary surface features so the learner recognizes the same idea in another setting.
4. **Move from guidance to independent work.** Model why a step is valid and why it helps. Then leave a meaningful step for the learner to supply, and follow with a problem that requires the same reasoning with less support. For proofs, order optional hints from a relevant definition to an intermediate goal to a plan. Keep the complete argument in the model solution.
5. **Use responses to decide what to teach next.** Write distractors from plausible mathematical errors and check that exactly one option is defensible under the stated assumptions. Explain the deciding idea in both feedback messages. Schema 0.1 has one incorrect feedback message and one incorrect branch, so address the shared distinction or explain the relevant alternatives together. A retry can help the learner practice. The first submission still determines the branch.
6. **Close with a fresh task and plan a later return.** Ask the learner to apply the same idea to a new case before seeing the answer. For later retrieval, change the mathematical case or reasoning demand while keeping the target skill. Reordering options or restating the same answer belongs to the same assessment family. For checked review in 0.1, plan at least two distinct families for the same objective within the same unit revision. The Android app looks for a family the learner has yet to attempt. Put fresh checks on different reachable branches so a learner can complete the initial path with a family still available.

An inquiry lesson can start with a conjecture and a counterexample. A lesson introducing unfamiliar notation can start with a worked example and move more quickly to a short application. Choose the order and the branches according to what the learner needs next.

### Worked lesson plan: Why two odd integers add to an even integer

**Prerequisites.** Learners can use the integer definitions of even and odd and factor out a common factor. **Objectives.** Recognize when an expression is guaranteed to be even and construct a proof about the sum of two arbitrary odd integers. Use separate objective IDs for recognition and proof construction.

| Activity | What the learner does and what the author supplies |
| --- | --- |
| `predict` · `choice` | Decide the parity of `2k + 2` for every integer `k`. Include “it depends on `k`” as a plausible distractor. Ask the learner to identify a factorization before choosing. Feedback shows `2(k + 1)` and explains why the factor in parentheses is an integer. Use this as a diagnostic. |
| `contrast` / `remediate` · `exposition` | After a correct answer, compare `2k + 2` with `2k + 1` and ask what changes the parity. After an incorrect answer, show the same contrast with a worked factorization and ask the learner to supply the integer witness in `2k + 4 = 2(…)`. |
| `product-check` / `negative-check` · `choice` | On the contrast path, given that `m` is an integer and `n` is even, choose which of `mn`, `m + n`, and `n + 1` must be even. The key is `mn` because `n = 2t` gives `mn = 2(mt)`. On the remediation path, choose which equality proves −14 is even, with `2(-7) = -14` as the key. Give the checks different families for the same recognition objective. Both checks continue to the proof, leaving the other family available for later review. |
| `prove` · `proof` | Prove the claim for any two odd integers. Offer hints in order: write each odd integer using its own integer parameter, combine the expressions, then look for twice an integer. The model uses `(2a + 1) + (2b + 1) = 2(a + b + 1)`. The rubric checks arbitrary inputs, valid reasoning, and why the result is even. It accommodates any valid argument. |
| `reflect` · `reflection`, then `finish` · `end` | Ask the learner how they would adapt their argument to find the parity of the sum of an odd and an even integer. Use the summary to explain how the integer witness and the remainder determine parity. |

Exact choice checks the selected answer. A prompt to reason before choosing can teach a useful habit, but the evaluator cannot inspect that reasoning. In schema 0.1, only `recall` and `recognition` objectives can receive checked evidence through a choice activity's `evidence` field. Proof comparison uses `self_review` and the proof remains `unassessed`. A hint-assisted attempt, a self-review, and an independent first-submission success each tell the author something different about the learner's work.

## What makes a good Axiora lesson

Use this checklist during author review, then work through the lesson in the app.

- **Clear target.** The main task elicits the stated skill. If the objective says “prove,” the learner has an opportunity to construct an argument before seeing the solution.
- **Sound mathematics.** Conventions, domains, and hypotheses are explicit. Examples illustrate the claim under those assumptions, and counterexamples satisfy the premises of the claim they refute. Check every answer key, hint, and solution by working the problem independently.
- **Purposeful examples and feedback.** Each example serves a specific distinction. The likely wrong answer has an intelligible reason behind it, and feedback gives the learner a next move they can try. A proof rubric describes what makes an argument valid and accommodates different approaches.
- **Independent practice.** The final check uses a fresh case, with enough information to solve it and without giving away the decisive reasoning. Later review provides another opportunity to retrieve and apply the idea.
- **Usable study paths.** Try each branch, a wrong answer followed by a retry, and the proof with and without help. Check that every path leaves the learner with a useful takeaway. Read the lesson on a narrow screen and check that notation and instructions stay understandable in the target app.
- **Traceable revision.** Verify provenance and rights, and record mathematical and teaching review against the actual unit revision before marking it reviewed.

### Research behind the authoring guidance

- Pair worked examples with prompts that ask why a step is valid, then gradually remove steps. Atkinson, Renkl, and Merrill tested this combination in two problem-solving experiments. [Atkinson, Renkl, and Merrill (2003)](https://doi.org/10.1037/0022-0663.95.4.774)
- Ask learners to compare the reasoning explicitly when presenting two approaches. In a seventh-grade equation-solving experiment, comparing methods improved procedural knowledge and flexibility relative to studying the methods separately. [Rittle-Johnson and Star (2007)](https://doi.org/10.1037/0022-0663.99.3.561)
- Ask learners to retrieve before rereading, and revisit material after a delay. Retrieval and spacing experiments measured benefits for delayed retention. Axiora's review intervals are product heuristics. [Roediger and Karpicke (2006)](https://doi.org/10.1111/j.1467-9280.2006.01693.x), [Cepeda et al. (2008)](https://doi.org/10.1111/j.1467-9280.2008.02209.x)

For Axiora's teaching rationale, field definitions, and a complete illustrative JSON package, see the [pedagogy handbook](../../docs/markdown/pedagogy.md), [content contract](../../docs/markdown/content.md), and [prime and maximal ideals fixture](../../examples/prime-and-maximal/README.md).

## Create a package

```sh
axiora init my-course --id example.course --title "My course"
axiora add unit my-course --id example.course.second --title "Second lesson" --concept example.course.concept
```

Both commands accept `--dry-run`. They refuse to overwrite existing destinations. `init` creates `manifest.json`, `concepts/core.json`, and `units/first.json`. `add unit` creates a draft exposition unit and adds it to the manifest and entry units. It requires an existing concept ID.

### Files in a starter package

- `manifest.json` identifies the package, lists its concepts and units, declares required capabilities, and records each payload's size and SHA-256 hash.
- `concepts/core.json` contains concept definitions and assumptions.
- `units/first.json` contains objectives, source records, and the lesson activity flow.

Edit the concept and unit files for your course. Edit the manifest when package metadata, unit listings, entry units, review status, or licensing information changes. After editing payload files, run `axiora refresh` to update their inventory records.

## Edit the JSON

Use a text editor or a JSON-aware tool. Keep valid JSON: strings use double quotes, literal newlines in strings must be escaped, and duplicate object keys are invalid. Keep the existing fields and follow the content contract for supported additions.

- In `concepts/core.json`, replace the concept title, definition, and assumptions with accurate content. The `id` is a stable identity; unit `concept_ids` and objective `concept_id` refer to it.
- In `units/first.json`, replace the title, objective description, source citation, locator, and rights placeholders. The `entry` field names the first key in `activities`; an exposition's `next` names its successor. Edit `content_md` and `summary_md` to teach and summarize the lesson. Text ending in `_md` contains Markdown inside a JSON string; escape backslashes used in LaTeX (for example, `"$\\mathbb{Z}$"`).
- In `manifest.json`, set the title, language, conventions, and truthful license and review information. The manifest lists concepts, units, entry units, capabilities, and an inventory of payload files. Keep IDs and references consistent. `files` stores each payload's raw byte count and SHA-256; do not include the manifest itself. Hashes detect changes and do not establish authenticity.

Source records must reflect actual provenance and rights. Do not claim review or a license without supporting evidence. Keep IDs stable when editing prose. Published package revisions are immutable: update package and unit versions as appropriate when revising content. Changes to answer keys, objectives, rubrics, or assessment meaning need a version and migration review; see the content contract.

## Mathematical notation

Use LaTeX math expressions in Markdown fields, with `$...$` for inline math and `$$...$$` for display math where the lesson renderer supports it. Because the Markdown is stored inside JSON strings, write each LaTeX backslash as `\\`. For example, the JSON value for inline integers is `"content_md": "The integers are $\\mathbb{Z}$."`.

Axiora's Rust math engine, Texlet, supports a subset of LaTeX that includes fractions, roots, scripts, Greek letters, and common operators. Actual display depends on the renderer and its font. Use the target app to review formulas visually, including the readable fallback for unsupported syntax. See the [Texlet notation roadmap](../texlet/ROADMAP.md).

For example, this JSON field stores an inline formula using the currently supported set notation:

```json
"content_md": "Every integer is in $\\mathbb{Z}$."
```

The starter unit illustrates exposition and an end node. When adding choices, proofs, reflections, or branches, check the required fields and capability declarations. Use `axiora schema` for a machine-readable summary and the handbook for the complete rules. [Content contract](../../docs/markdown/content.md)

## Check and package

```sh
axiora refresh my-course --dry-run
axiora refresh my-course
axiora validate my-course --format json
axiora pack my-course --output my-course.axp
axiora inspect my-course.axp
```

After editing payloads, `refresh` recalculates their sizes and hashes. It requires all other validation checks to pass and leaves the manifest unchanged on failure. `validate` emits diagnostics with codes, file paths and field paths; fix reported issues before packaging. The archive and adjacent report must be written outside the content directory. Existing output is not replaced without `--overwrite`.

Use `axiora diff old-directory new-directory --format json` to compare the structure of validated revisions. Both directories and archives are accepted. Validation covers JSON shape, references, flow, capabilities, and inventory integrity. Review mathematical meaning, teaching quality, rights, and display separately. For learner-facing checks, import the archive into the app and study it there. The CLI has no lesson preview or author-defined lesson tests.

## Troubleshooting

- **JSON parse error:** check quotation marks, commas, braces, and escaped characters. A backslash inside a JSON string must be doubled; literal line breaks inside strings must also be escaped.
- **Unknown concept or unit ID:** IDs are references. Make sure each referenced concept or unit exists and that spelling and capitalization match exactly.
- **Activity target or entry error:** the unit's `entry` must name an activity, and each transition target must name an existing activity. Check for misspelled IDs and disconnected activities.
- **Inventory size or hash mismatch:** after editing a concept or unit payload, run `axiora refresh my-course`, then validate again. Refresh updates payload inventory records; it does not fix other validation errors.
- **Unsupported capability or notation:** make sure the package declares the capabilities its activities require. Texlet supports only a subset of LaTeX; consult the notation roadmap and simplify or rewrite unsupported expressions.
- **Refresh fails:** fix validation errors first. Refresh only updates inventory after the rest of the package is valid, and supports `--dry-run` to preview its changes.
