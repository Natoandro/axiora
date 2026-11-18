package mg.abela.axiora.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
import mg.abela.axiora.mathspike.MathCanvas
import mg.abela.axiora.storage.StoredNote
import java.time.Instant
import java.util.concurrent.Executors

class StudyActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout
    private var current: StudyView? = null
    private var editor: EditText? = null
    private var status: TextView? = null
    private var draftSequence = 0L
    private var busy = false
    private var stopped = false
    private val autosave = Runnable { saveDraft() }
    private val font by lazy { runCatching { assets.open("math.otf").use { it.readBytes() } }.getOrNull() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        screen("Axiora")
        label("Opening local library…")
        val session = savedInstanceState?.getString("session")
        if (session == null) home() else work({ resume(session) }, success = ::study)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        current?.let { outState.putString("session", it.session.id) }
    }

    override fun onStop() {
        main.removeCallbacks(autosave)
        val view = current
        val text = editor?.text?.toString()
        if (busy && view != null && text != null && text != view.session.activityProgress().draft) {
            enqueue({ flushDraft(view, text) }) { result ->
                val saved = result.getOrNull()
                if (!stopped && current?.session?.id == view.session.id && editor?.text?.toString() == text) {
                    if (saved != null && current?.session?.currentActivityId == saved.session.currentActivityId) {
                        current = saved
                        draftSequence = maxOf(draftSequence, saved.session.activityProgress().draftRevision)
                        status?.text = "Draft saved on this device"
                    } else if (result.isFailure) {
                        status?.text = "Draft unsaved. Use Save draft to retry."
                    }
                }
            }
        } else {
            saveDraft()
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacks(autosave)
        super.onDestroy()
    }

    @Deprecated("Used with the framework document picker.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            IMPORT -> {
                val cancelled =
                    java.util.concurrent.atomic
                        .AtomicBoolean()
                val progress =
                    AlertDialog
                        .Builder(this)
                        .setMessage("Validating course…")
                        .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
                        .setOnCancelListener { cancelled.set(true) }
                        .create()
                progress.show()
                work({
                    runCatching { preview(uri) }.mapCatching { preview ->
                        if (cancelled.get()) {
                            discard(preview)
                            null
                        } else {
                            preview
                        }
                    }
                }) { result ->
                    progress.dismiss()
                    if (!cancelled.get()) result.fold({ it?.let(::confirmImport) }, { showError(it) })
                }
            }

            EXPORT -> {
                work({ export(uri) }) { notice("Backup saved", "Your learner records were written to the selected file.") }
            }

            RESTORE -> {
                work({ restore(uri) }) { missing ->
                    home()
                    notice(
                        "Backup restored",
                        if (missing == 0) {
                            "Learner records restored."
                        } else {
                            "$missing course revisions are missing. Reimport the original revisions to resume. History remains available."
                        },
                    )
                }
            }
        }
    }

    @Deprecated("Framework back navigation.")
    override fun onBackPressed() {
        if (busy) return
        if (current != null) saveDraft(::home) else super.onBackPressed()
    }

    private fun home() {
        work({ library() }) { library ->
            current = null
            screen("Axiora")
            val unfinished = library.sessions.filter { it.status == SessionStatus.ACTIVE || it.status == SessionStatus.PAUSED }
            unfinished.firstOrNull()?.let { item ->
                heading("Continue learning")
                action(item.title) { work({ resume(item.id) }, success = ::study) }
            }
            if (library.reviews.isNotEmpty()) heading("Due review")
            library.reviews.take(3).forEach { review ->
                val objective =
                    review.unit.text
                        .path("objectives")
                        .firstOrNull {
                            it.path("id").asText() == review.task.objectiveId
                        }?.path("description")
                        ?.asText()
                        .orEmpty()
                label("$objective\nDue ${review.task.dueDate}")
                if (review.freshActivity == null) {
                    label("A fresh check is unavailable. You can revisit this unit for practice.")
                    action("Practice · ${review.unit.title}") { work({ start(review.unit) }, success = ::study) }
                } else {
                    action("Start fresh check · ${review.unit.title}") {
                        work({ start(review.unit, review.freshActivity) }, success = ::study)
                    }
                }
            }
            heading("Course library")
            if (library.courses.isEmpty()) label("Import an Axiora course package (.axp) to study offline.")
            action("Import course") { pick(IMPORT) }
            library.courses.forEach { entry ->
                val course = entry.course
                if (course == null) {
                    label(
                        "Missing course · ${entry.record.packageId} ${entry.record.packageVersion}\nReimport this exact revision to resume.",
                    )
                } else {
                    action("${course.title} · ${course.descriptor.version}") { course(course) }
                }
            }
            if (library.sessions.isNotEmpty()) {
                heading("Study history")
                library.sessions.forEach { entry ->
                    val revision = entry.revision?.packageVersion
                    val state = entry.status?.name?.lowercase() ?: "missing course"
                    action("${entry.title} · $revision · $state") { history(entry) }
                }
            }
            action("Notes") { notes() }
            heading("Learner data")
            action("Export learner backup") {
                confirm(
                    "Export personal data?",
                    "The backup is readable JSON containing proofs, drafts and notes. Choose where it will be saved.",
                    "Choose destination",
                ) { pick(EXPORT) }
            }
            action("Restore learner backup") {
                confirm(
                    "Restore learner backup?",
                    "Restore requires an empty learner profile. Course files are imported separately.",
                    "Choose backup",
                ) { pick(RESTORE) }
            }
        }
    }

    private fun course(course: Course) {
        screen(course.title)
        action("Library", ::home)
        label("Version ${course.descriptor.version} · Review claim: ${course.review}")
        content(course.conventions)
        label(
            course.manifest
                .path("review")
                .path("note")
                .asText(),
        )
        heading("Units")
        course.units.forEach { unit ->
            heading(unit.title)
            val prerequisites = unit.text.path("requires_concepts").map { it.asText() }
            if (prerequisites.isNotEmpty()) label("Assumed knowledge: ${prerequisites.joinToString()}")
            action("Start ${unit.title}") { work({ start(unit) }, success = ::study) }
        }
        action("Remove this revision from library") {
            confirm("Remove from library?", "Existing attempts, notes and pinned sessions remain available.", "Remove") {
                work({
                    store.setPackageActive(course.descriptor.packageId, course.descriptor.version, false)
                }) { home() }
            }
        }
    }

    private fun confirmImport(preview: ImportPreview) {
        val course = preview.course
        val prerequisites = course.units.flatMap { it.text.path("requires_concepts").map { item -> item.asText() } }.distinct()
        val versions = preview.existing.filter { it.active }.joinToString { it.packageVersion }
        val message =
            buildString {
                appendLine("${course.descriptor.packageId} · ${course.descriptor.version}")
                appendLine("Review claim: ${course.review}")
                appendLine(
                    course.manifest
                        .path("review")
                        .path("note")
                        .asText(),
                )
                appendLine(course.conventions)
                if (prerequisites.isNotEmpty()) appendLine("Assumed knowledge: ${prerequisites.joinToString()}")
                if (versions.isNotEmpty()) {
                    appendLine(
                        "Installed versions: $versions. You can start work on either revision. In-progress sessions stay pinned.",
                    )
                }
            }
        val cancel = { work({ discard(preview) }) {} }
        AlertDialog
            .Builder(this)
            .setTitle(course.title)
            .setMessage(message)
            .setNegativeButton(if (versions.isEmpty()) "Cancel" else "Keep existing") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .setPositiveButton("Import course") { _, _ -> work({ install(preview) }) { home() } }
            .show()
    }

    private fun history(entry: SessionEntry) {
        work({ store.attemptHistory(entry.id) }) { attempts ->
            screen(entry.title)
            action("Library", ::home)
            if (entry.status == SessionStatus.ACTIVE || entry.status == SessionStatus.PAUSED) {
                action("Continue existing work") { work({ resume(entry.id) }, success = ::study) }
            }
            if (entry.status == null) label("Reimport this exact course revision to resume.")
            heading("Attempts")
            if (attempts.isEmpty()) label("No submitted attempts.")
            attempts.forEach { attempt ->
                heading("${attempt.method.replace('_', ' ')} · ${attempt.outcome}")
                content(attempt.contextSnapshot)
                label(attempt.response, selectable = true)
                val assistance = org.json.JSONObject(attempt.assistance.toString(Charsets.UTF_8))
                label(
                    "Hints opened: ${assistance.optJSONArray("revealed_hints")?.length() ?: 0}. " +
                        "Solution opened: ${if (assistance.optBoolean("solution_revealed")) "yes" else "no"}.",
                )
            }
        }
    }

    private fun notes(anchor: String? = null) {
        work({ store.notes(anchor) }) { notes ->
            screen("Notes")
            action("Library", ::home)
            if (notes.isEmpty()) label("No notes yet.")
            notes.forEach { note ->
                heading(note.contentId)
                label(note.text, selectable = true)
                action("Edit note") { editNote(note) }
            }
            action("New note") {
                val now = Instant.now().toString()
                editNote(StoredNote(newId(), anchor ?: "personal", null, "notes", "", now, now))
            }
        }
    }

    private fun editNote(note: StoredNote) {
        val field =
            EditText(this).apply {
                setText(note.text)
                hint = "Note"
                minLines = 4
            }
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle("Note")
                .setView(field)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = field.text.toString()
                work({ store.saveNote(note.copy(text = text, modifiedAt = Instant.now().toString())) }) {
                    dialog.dismiss()
                    notes()
                }
            }
        }
        dialog.show()
    }

    private fun study(view: StudyView) {
        current = view
        val session = view.session
        val progress = session.activityProgress()
        draftSequence = progress.draftRevision
        screen(view.unit.title)
        action("Save and leave") { saveDraft(::home) }
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
        when (session.unit.activities.getValue(session.currentActivityId)) {
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

    private fun choice(
        text: JsonNode,
        progress: ActivityProgress,
    ) {
        content(text.path("prompt_md").asText())
        val options = RadioGroup(this)
        val ids = mutableMapOf<Int, String>()
        text.path("options").forEach { option ->
            val button =
                RadioButton(this).apply {
                    id = View.generateViewId()
                    minHeight = dp(48)
                    this.text = option.path("text_md").asText()
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

    private fun proof(
        text: JsonNode,
        progress: ActivityProgress,
    ) {
        content(text.path("prompt_md").asText())
        text
            .path("hints")
            .filter { it.path("id").asText() in progress.assistance.revealedHints }
            .forEach { content(it.path("content_md").asText()) }
        if (progress.proofSubmitted && progress.draft != progress.attempts.last().response) {
            proofEditor(text, progress, true)
        } else if (progress.proofSubmitted) {
            heading("Your submitted proof")
            label(progress.attempts.last().response, selectable = true)
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
                val group = RadioGroup(this)
                val ids = mutableMapOf<Int, RubricMark>()
                RubricMark.entries.forEach { mark ->
                    val button =
                        RadioButton(this).apply {
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

    private fun proofEditor(
        text: JsonNode,
        progress: ActivityProgress,
        revision: Boolean,
    ) {
        if (revision) {
            screen("Revise proof")
            action("Save and leave") { saveDraft(::home) }
            content(text.path("prompt_md").asText())
        }
        heading("Proof draft")
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
                Button(this).apply {
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
            AlertDialog
                .Builder(this)
                .setTitle("Draft preview")
                .setMessage(field.text.toString())
                .setPositiveButton("Return to draft", null)
                .show()
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

    private fun reflection(
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

    private fun next() {
        action("Continue") { send(StudyCommand.Continue(newId())) }
    }

    private fun saveDraft(after: (() -> Unit)? = null) {
        main.removeCallbacks(autosave)
        val view = current
        val field = editor
        if (view == null || field == null) {
            after?.invoke()
            return
        }
        if (busy) {
            if (after == null) main.postDelayed(autosave, 450)
            return
        }
        val text = field.text.toString()
        if (text == view.session.activityProgress().draft) {
            if (view.session.activityProgress().draftRevision > 0) status?.text = "Draft saved on this device"
            after?.invoke()
            return
        }
        val command = StudyCommand.SaveProofDraft(newId(), text, ++draftSequence)
        status?.text = "Saving draft…"
        if (after != null) field.isEnabled = false
        work({ send(view, command) }, failure = {
            field.isEnabled = true
            status?.text = "Draft unsaved. Use Save draft to retry."
        }) { saved ->
            current = saved
            field.isEnabled = true
            if (field.text.toString() == text) {
                status?.text = "Draft saved on this device"
                after?.invoke()
            } else {
                status?.text = "Unsaved changes"
                main.postDelayed(autosave, 450)
            }
        }
    }

    private fun send(
        command: StudyCommand,
        then: (() -> Unit)? = null,
    ) {
        val view = current ?: return
        work({ send(view, command) }) { saved ->
            if (then == null) {
                study(saved)
            } else {
                current = saved
                then()
            }
        }
    }

    private fun <T> work(
        operation: StudyWorkspace.() -> T,
        failure: (Throwable) -> Unit = ::showError,
        success: (T) -> Unit,
    ) {
        if (busy) return
        busy = true
        enqueue(operation) { result ->
            busy = false
            if (!stopped) result.fold(success) { failure(it) }
        }
    }

    private fun <T> enqueue(
        operation: StudyWorkspace.() -> T,
        complete: (Result<T>) -> Unit,
    ) {
        val context = applicationContext
        worker.execute {
            val result =
                try {
                    val workspace = sharedWorkspace ?: StudyWorkspace(context).also { sharedWorkspace = it }
                    Result.success(workspace.operation())
                } catch (error: Exception) {
                    Result.failure(error)
                }
            main.post { complete(result) }
        }
    }

    private fun screen(title: String) {
        editor = null
        status = null
        val scroll = ScrollView(this).apply { isFillViewport = true }
        column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(32))
            }
        scroll.addView(
            column,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        if (resources.displayMetrics.widthPixels > dp(760)) column.layoutParams.width = dp(760)
        setContentView(scroll)
        heading(title, 30f)
        this.title = title
    }

    private fun heading(
        value: String,
        size: Float = 22f,
    ) {
        label(value, size).apply {
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
    }

    private fun label(
        value: String,
        size: Float = 17f,
        selectable: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextIsSelectable(selectable)
            setPadding(0, dp(8), 0, dp(8))
            column.addView(this)
        }

    private fun content(source: String) {
        if (source.isBlank()) return
        // Source is always selectable. Native previews are supplemental and contain no links or scripts.
        label(source, selectable = true)
        val match = MATH.findAll(source).distinctBy { it.value }.take(12)
        match.forEach { formula ->
            val expression = formula.groups[1]?.value ?: formula.groups[2]?.value ?: return@forEach
            val display = formula.groups[1] != null
            val preview =
                font?.takeIf { expression.length <= 512 }?.let { data ->
                    runCatching { MathCanvas.create(this, data, expression.trim(), display) }.getOrNull()
                }
            if (preview == null) {
                label("Formula preview unavailable · $expression", selectable = true)
            } else if (preview.formulaWidth() > minOf(resources.displayMetrics.widthPixels, dp(760)) - dp(48)) {
                label("Formula source · $expression", selectable = true)
            } else {
                preview.contentDescription = "Formula preview. LaTeX source: $expression"
                preview.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                preview.isFocusable = true
                column.addView(preview)
            }
        }
    }

    private fun action(
        title: String,
        click: () -> Unit,
    ): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 16f
            minHeight = dp(48)
            setOnClickListener { if (!busy) click() }
            column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun writingField(
        title: String,
        value: String,
    ): EditText =
        EditText(this).apply {
            hint = title
            contentDescription = title
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 12
            // Imported checkpoints can contain longer text than this editor allows for new input.
            setText(value)
            filters = arrayOf(android.text.InputFilter.LengthFilter(12_000))
            column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun notice(
        title: String,
        message: String,
    ) {
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showError(error: Throwable) {
        if (error is LinkageError) android.util.Log.e("Axiora", "Android runtime incompatibility", error)
        val message =
            if (error is IllegalArgumentException || error is IllegalStateException) {
                error.message?.take(900) ?: "Try the action again."
            } else {
                "Check that the selected file and local storage are available, then try again."
            }
        notice("Action could not be completed", message)
    }

    private fun confirm(
        title: String,
        message: String,
        action: String,
        onConfirm: () -> Unit,
    ) {
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .show()
    }

    private fun pick(operation: Int) {
        val intent =
            Intent(if (operation == EXPORT) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .setType(if (operation == EXPORT) "application/json" else "*/*")
        if (operation == EXPORT) intent.putExtra(Intent.EXTRA_TITLE, "axiora-learner-backup.json")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, operation)
        } catch (_: android.content.ActivityNotFoundException) {
            notice("File picker unavailable", "This device has no system document picker.")
        }
    }

    private companion object {
        const val IMPORT = 11
        const val EXPORT = 12
        const val RESTORE = 13
        val MATH = Regex("""\$\$([^$]+)\$\$|\$([^$\n]+)\$""")
        val worker = Executors.newSingleThreadExecutor()
        var sharedWorkspace: StudyWorkspace? = null
    }
}
