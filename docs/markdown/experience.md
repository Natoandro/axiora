# Study experience

## A quiet workspace with a clear next step

The Android experience should prioritize reading, mathematical notation, and recoverable writing. It should feel comfortable during a long proof attempt and remain useful for a five-minute review. This is a functional interaction design, not an approved visual mockup or finished application.

Use native Android navigation, accessibility, text selection, and lifecycle conventions. Avoid a dense graph as the first screen. A map becomes useful only after the learner has enough concepts to navigate.

## Library and home

With no content installed, explain what a course package is and offer **Import course**. Do not show an empty dashboard full of invented metrics. An imported course shows its title, assumptions, prerequisites, version, and review claim.

With work in progress, home prioritizes **Continue learning**, followed by due reviews and the course library. A course can be browsed by unit or concept. Notes and unfinished proof attempts remain reachable without restarting a lesson.

Do not show a single mastery ring or a completion percentage as a proxy for understanding. Prefer concrete language: “Two independent recognition checks,” “Proof attempt awaiting comparison,” and “Review due today.”

## Import flow

1. Select a local `.axp` file through the system file picker.
2. Show validation progress with cancellation before installation commits.
3. Present identity, conventions, review status, prerequisite gaps, and update effects.
4. Offer **Import course** or **Keep existing version** where relevant.
5. On success, show the available starting units. On failure, preserve the existing library and show a diagnostic with a recovery action.

Example error: “This course needs a newer content reader. Your existing work is safe. Install a compatible app version or choose another package.” Avoid exposing stack traces in learner-facing flows.

## Guided activity

Present one primary task at a time with its objective accessible nearby. A single-choice question uses native selection semantics and a separate **Submit answer** action. Feedback explains the mathematical issue and offers **Try again** or **Continue**. The first submitted result determines the authored branch.

The learner can inspect relevant definitions; opening that help records assistance if the task is being assessed. UI navigation controls must not accidentally reveal the correct answer or resubmit an attempt.

History is readable without mutating the active checkpoint. A learner may leave a session and return to the same selected answer or draft.

## Proof workspace

Use a text editor with readable LaTeX source and an optional preview. Provide common mathematical symbols, undo, selection, and copy. A working save indicator is more important than a sophisticated equation keyboard.

The proof prompt remains available while writing. On a phone, use a clearly labeled prompt panel rather than squeezing two columns onto the screen; on larger devices, a split workspace may be appropriate. Validate the layout with real long equations and long proof drafts.

Actions:

- **Save and leave:** preserve the draft and return home.
- **Show a hint:** reveal the next hint and record it without changing the draft.
- **Compare with solution:** snapshot the current attempt, reveal the model solution, and show the rubric.
- **Skip for now:** record the skip and continue without inventing a score.

During comparison, keep the learner's argument visible or one tap away. The rubric supports **Met**, **Not met**, and **Unsure**, plus a reminder that an alternative proof can be valid. Revision creates a new attempt linked to the earlier one; it does not overwrite history.

## Reviews

A review explains why it is offered and what will be checked. A fresh assessment family is preferred over repeating the exact same question. If no fresh check exists, label the task as practice.

Learners can defer reviews or choose a smaller session. Overdue work should be grouped into a manageable choice rather than a punitive backlog. No streak loss, countdown pressure, or synthetic reward is required.

## Essential states

| State | Learner sees | Recovery |
| --- | --- | --- |
| No courses | Brief explanation and import action | Select a package |
| Missing prerequisite | Named assumed concept | Read a reminder or browse available remedial units |
| Draft saving | Honest saving/saved status | Stay on task |
| Save failed | Current text retained, unsaved status | Retry or explicitly copy/export |
| Unsupported formula | Readable LaTeX source and diagnostic | Continue or report the issue |
| Content update | Old and new versions, consequences | Continue pinned work or start the updated unit |
| No fresh review task | Practice label | Practice or select another topic |
| No network | Normal core behavior | No connectivity prompt is needed |

## Accessibility and inclusion

Support system font scaling, sufficient contrast, keyboard focus, TalkBack labels, and large touch targets. Never rely on color alone for outcomes. Announce save failures and assessment feedback accessibly without stealing focus from the editor.

Mathematical accessibility needs a dedicated spike: spoken expressions, selectable source, reading order, and line navigation must be evaluated with real assistive technology. Rendering a visual formula does not satisfy that requirement. Provide plain-language descriptions where practical and retain access to the mathematical source.

The first content language is English. Keep interface strings localizable and store the package language, but defer translated curriculum and right-to-left notation combinations until they can be tested. Do not put hard-coded English feedback in evaluator logic; feedback belongs to the content or localized UI.

## Optional AI interaction later

An optional **Discuss this attempt** action can prepare a preview containing the prompt, learner-selected proof text, and selected course references. The learner sees the destination provider and sends deliberately. They can edit or remove excerpts.

Show the resulting feedback as an unverified external suggestion. It may ask questions or point out possible gaps. It must not change the saved attempt, correctness method, or mastery summary automatically. The core session remains usable if the provider is unavailable.
