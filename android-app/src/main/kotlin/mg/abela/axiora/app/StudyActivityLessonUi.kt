package mg.abela.axiora.app

import android.app.AlertDialog
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.fasterxml.jackson.databind.JsonNode
import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.End
import mg.abela.axiora.content.Exposition
import mg.abela.axiora.content.Proof
import mg.abela.axiora.content.Reflection
import mg.abela.axiora.learning.ActivityProgress
import mg.abela.axiora.learning.AttemptOutcome
import mg.abela.axiora.learning.RubricMark
import mg.abela.axiora.learning.SessionStatus
import mg.abela.axiora.learning.StudyCommand

/** Lesson, question, proof editor and proof comparison screens. */
internal abstract class StudyActivityLessonUi : StudyActivityLibraryUi() {
    protected abstract val main: Handler
    protected abstract val autosave: Runnable
    protected abstract var draftSequence: Long
    protected abstract fun saveDraft(after: (() -> Unit)? = null)
    protected abstract fun send(command: StudyCommand, then: (() -> Unit)? = null)

    protected fun study(view: StudyView) {
        current = view
        val session = view.session
        val progress = session.activityProgress()
        draftSequence = progress.draftRevision
        val activity = session.unit.activities.getValue(session.currentActivityId)
        val title = when (activity) {
            is Choice -> "Recognition question"
            is Proof -> if (progress.proofSubmitted) "Proof workspace" else "Write a proof"
            is Reflection -> "Reflection"
            is End -> "Unit complete"
            is Exposition -> view.unit.title
        }
        screen(title)
        action("‹  Save and leave") { saveDraft(::home) }
        val text = view.unit.activity(session.currentActivityId)
        if (session.status == SessionStatus.COMPLETED) {
            heading("Unit complete")
            content(text.path("summary_md").asText())
            action("Return to library", ::home)
            return
        }
        val objectives =
            view.unit.text
                .path("objectives")
                .joinToString("\n") { it.path("description").asText() }
        if (objectives.isNotBlank()) label(objectives)
        if (activity is Choice || activity is Proof) label(view.unit.title, 14f)
        when (activity) {
            is Exposition -> {
                content(text.path("content_md").asText())
                next()
            }

            is Choice -> {
                choice(text, progress)
            }

            is Proof -> {
                proof(text, progress)
            }

            is Reflection -> {
                reflection(text, progress)
            }

            is End -> {
                content(text.path("summary_md").asText())
                action("Finish unit") { send(StudyCommand.Continue(newId())) }
            }
        }
    }

    protected fun choice(
        text: JsonNode,
        progress: ActivityProgress,
    ) {
        content(text.path("prompt_md").asText())
        label("Choose one answer", 14f)
        val options = RadioGroup(this)
        val ids = mutableMapOf<Int, String>()
        text.path("options").forEach { option ->
            val button =
                RadioButton(this).apply {
                    id = View.generateViewId()
                    minHeight = dp(48)
                    setMathText(option.path("text_md").asText())
                }
            val key = option.path("id").asText()
            ids[button.id] = key
            options.addView(button)
            if (key == progress.selectedOption) options.check(button.id)
        }
        column.addView(options)
        val attempt = progress.attempts.lastOrNull()
        if (progress.feedbackVisible && attempt != null) {
            val correct = attempt.outcome == AttemptOutcome.CORRECT
            heading(if (correct) "Correct · exact choice" else "Incorrect · exact choice")
            content(text.path("feedback").path(if (correct) "correct_md" else "incorrect_md").asText())
            if (attempt.sequence > 1) label("Retry · practice")
            next()
        }
        val submit =
            action(if (attempt == null) "Submit answer" else "Try again") {
                send(StudyCommand.SubmitChoice(newId()))
            }
        submit.isEnabled = progress.selectedOption != null && !progress.feedbackVisible
        options.setOnCheckedChangeListener { _, id ->
            ids[id]?.let { send(StudyCommand.SelectChoice(newId(), it)) }
        }
    }

    protected fun proof(
        text: JsonNode,
        progress: ActivityProgress,
    ) {
        if (progress.proofSubmitted && progress.assistance.solutionRevealed) {
            comparison(text, progress)
            return
        }
        heading("Proof prompt", 17f)
        content(text.path("prompt_md").asText())
        text
            .path("hints")
            .filter { it.path("id").asText() in progress.assistance.revealedHints }
            .forEach { content(it.path("content_md").asText()) }
        if (progress.proofSubmitted && progress.draft != progress.attempts.last().response) {
            proofEditor(text, progress, true)
        } else if (progress.proofSubmitted) {
            heading("Your submitted proof")
            content(progress.attempts.last().response)
            label(
                "Self-review · unassessed\nHints opened: ${progress.assistance.revealedHints.size}. " +
                    "Solution opened: ${if (progress.assistance.solutionRevealed) "yes" else "no"}.",
            )
            if (!progress.assistance.solutionRevealed) {
                action("Show model solution") { send(StudyCommand.RevealSolution(newId())) }
            }
            if (progress.assistance.solutionRevealed) {
                heading("Model solution")
                content(text.path("model_solution_md").asText())
            }
            heading("Compare with the rubric")
            label("An alternative proof can be valid. Use these criteria to review your own argument.")
            val marks = progress.rubric.toMutableMap()
            text.path("rubric").forEach { item ->
                val key = item.path("id").asText()
                content(item.path("criterion_md").asText())
                val group = RadioGroup(this@StudyActivityLessonUi)
                val ids = mutableMapOf<Int, RubricMark>()
                RubricMark.entries.forEach { mark ->
                    val button =
                        RadioButton(this@StudyActivityLessonUi).apply {
                            id = View.generateViewId()
                            minHeight = dp(48)
                            this.text =
                                when (mark) {
                                    RubricMark.MET -> "Met"
                                    RubricMark.NOT_MET -> "Not met"
                                    RubricMark.UNSURE -> "Unsure"
                                }
                        }
                    ids[button.id] = mark
                    group.addView(button)
                    if (marks[key] == mark) group.check(button.id)
                }
                group.setOnCheckedChangeListener { _, id -> ids[id]?.let { marks[key] = it } }
                column.addView(group)
            }
            action("Save self-review and continue") {
                send(StudyCommand.RecordRubric(newId(), marks.toMap()), then = { send(StudyCommand.Continue(newId())) })
            }
            action("Revise proof") { proofEditor(text, progress, true) }
        } else {
            proofEditor(text, progress, false)
        }
    }

    protected fun comparison(
        text: JsonNode,
        progress: ActivityProgress,
    ) {
        screen("Compare your proof")
        action("‹  Proof workspace") { proofEditor(text, progress, true) }
        label(current?.unit?.title.orEmpty(), 14f)
        card {
            heading("Your submitted proof", 18f)
            content(progress.attempts.lastOrNull()?.response.orEmpty(), this)
        }
        heading("Model solution", 21f)
        content(text.path("model_solution_md").asText())
        heading("Self-review", 21f)
        label("A direct proof can also be valid. Use these criteria to check your own argument. Correctness remains unassessed.", 14f)
        val marks = progress.rubric.toMutableMap()
        text.path("rubric").forEach { item ->
            val key = item.path("id").asText()
            card {
                content(item.path("criterion_md").asText(), this)
                val group = RadioGroup(this@StudyActivityLessonUi)
                val ids = mutableMapOf<Int, RubricMark>()
                RubricMark.entries.forEach { mark ->
                    val button = RadioButton(this@StudyActivityLessonUi).apply {
                        id = View.generateViewId()
                        minHeight = dp(48)
                        this.text = when (mark) {
                            RubricMark.MET -> "Met"
                            RubricMark.NOT_MET -> "Not met"
                            RubricMark.UNSURE -> "Unsure"
                        }
                    }
                    ids[button.id] = mark
                    group.addView(button)
                    if (marks[key] == mark) group.check(button.id)
                }
                group.setOnCheckedChangeListener { _, id -> ids[id]?.let { marks[key] = it } }
                addView(group)
            }
        }
        primaryAction("Save self-review and continue") {
            send(StudyCommand.RecordRubric(newId(), marks.toMap()), then = { send(StudyCommand.Continue(newId())) })
        }
        action("Revise proof") { proofEditor(text, progress, true) }
    }

    protected fun proofEditor(
        text: JsonNode,
        progress: ActivityProgress,
        revision: Boolean,
    ) {
        if (revision) {
            screen("Revise proof")
            action("Save and leave") { saveDraft(::home) }
            content(text.path("prompt_md").asText())
        }
        label("Your proof remains saved on this device. Self review does not certify correctness.", 14f)
        val field = writingField("Proof draft", progress.draft)
        editor = field
        status =
            label(if (progress.draftRevision > 0) "Draft saved on this device" else "No draft saved yet").apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        field.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    status?.text = "Unsaved changes"
                    main.removeCallbacks(autosave)
                    main.postDelayed(autosave, 450)
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )
        val symbols = LinearLayout(this)
        listOf("∈", "⊂", "⇒", "∀", "∃").forEach { symbol ->
            symbols.addView(
                Button(this@StudyActivityLessonUi).apply {
                    textSize = 18f
                    this.text = symbol
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        val start = field.selectionStart.coerceAtLeast(0)
                        val end = field.selectionEnd.coerceAtLeast(0)
                        field.text.replace(minOf(start, end), maxOf(start, end), symbol)
                    }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f),
            )
        }
        column.addView(symbols)
        action("Save draft") { saveDraft() }
        action("Preview draft") {
            val source = field.text.toString()
            val dialog =
                AlertDialog
                    .Builder(this)
                    .setTitle("Draft preview")
                    .setMessage(source)
                    .setPositiveButton("Return to draft", null)
                    .show()
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                setTextIsSelectable(true)
                post { setMathText(source) }
            }
        }
        if (!revision) {
            text.path("hints").firstOrNull { it.path("id").asText() !in progress.assistance.revealedHints }?.let { hint ->
                action("Show a hint") { saveDraft { send(StudyCommand.RevealHint(newId(), hint.path("id").asText())) } }
            }
        }
        action(if (revision) "Compare revised proof" else "Compare with solution") {
            saveDraft {
                send(StudyCommand.SubmitProof(newId()), then = { send(StudyCommand.RevealSolution(newId())) })
            }
        }
        if (!revision) {
            action("Skip for now") {
                saveDraft { send(StudyCommand.SkipActivity(newId()), then = { send(StudyCommand.Continue(newId())) }) }
            }
        }
    }

    protected fun reflection(
        text: JsonNode,
        progress: ActivityProgress,
    ) {
        content(text.path("prompt_md").asText())
        val field = writingField("Optional reflection", progress.reflection.orEmpty())
        action("Save reflection and continue") {
            send(StudyCommand.RecordReflection(newId(), field.text.toString()), then = { send(StudyCommand.Continue(newId())) })
        }
        action("Continue without reflection") { send(StudyCommand.Continue(newId())) }
    }

    protected fun next() {
        action("Continue") { send(StudyCommand.Continue(newId())) }
    }

}
