package mg.abela.axiora.app

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import mg.abela.axiora.learning.SessionStatus
import mg.abela.axiora.storage.StoredNote
import java.time.Instant

internal const val IMPORT_DOCUMENT_REQUEST = 11
internal const val EXPORT_DOCUMENT_REQUEST = 12
internal const val RESTORE_DOCUMENT_REQUEST = 13

/** Home, library, course, import, history and notes screens. */
internal abstract class StudyActivityLibraryUi : StudyActivityUi() {
    protected var pendingImport: ImportPreview? = null
    protected abstract var current: StudyView?

    protected abstract fun <T> work(
        operation: StudyWorkspace.() -> T,
        failure: (Throwable) -> Unit = ::showError,
        success: (T) -> Unit,
    )
    protected abstract fun pick(operation: Int)
    protected abstract fun confirm(title: String, message: String, action: String, onConfirm: () -> Unit)
    protected abstract fun showError(error: Throwable)
    protected abstract fun showStudy(view: StudyView)

    protected fun home() {
        work({ library() }) { library ->
            current = null
            screen("Home", "Home")
            label("Pick up where you left off.", 15f)
            val unfinished = library.sessions.filter { it.status == SessionStatus.ACTIVE || it.status == SessionStatus.PAUSED }
            unfinished.firstOrNull()?.let { item ->
                card {
                    heading(item.title, 25f)
                    label("Continue your saved session", 14f)
                    primaryAction("Continue learning") { work({ resume(item.id) }, success = ::showStudy) }
                }
            }
            heading("Due review")
            if (library.reviews.isEmpty()) entry("No reviews due today.", bottomRule = true)
            library.reviews.take(3).forEach { review ->
                val objective =
                    review.unit.text
                        .path("objectives")
                        .firstOrNull {
                            it.path("id").asText() == review.task.objectiveId
                        }?.path("description")
                        ?.asText()
                        .orEmpty()
                entry("$objective", "Due ${review.task.dueDate}")
                if (review.freshActivity == null) {
                    label("A fresh check is unavailable. You can revisit this unit for practice.")
                    action("Practice · ${review.unit.title}") { work({ start(review.unit) }, success = ::showStudy) }
                } else {
                    action("Start fresh check · ${review.unit.title}") {
                        work({ start(review.unit, review.freshActivity) }, success = ::showStudy)
                    }
                }
            }
            heading("Also in progress")
            if (unfinished.size > 1) unfinished.drop(1).forEach { item ->
                catalogRow(item.title, "Continue learning") { work({ resume(item.id) }, success = ::showStudy) }
            }
            if (unfinished.size > 1) textAction("See all in progress") { libraryScreen(showInProgress = true) }
        }
    }

    protected fun libraryScreen(showInProgress: Boolean = false) {
        work({ library() }) { library ->
            current = null
            screen("Library", "Library", showTitle = false)
            val intro = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            column.addView(intro)
            heading("Library", 30f, parent = intro).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            action("Import course", intro) { pick(IMPORT_DOCUMENT_REQUEST) }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) }
                maxWidth = resources.displayMetrics.widthPixels / 2
            }
            label("Browse courses installed on this device.", 15f)
            val search = EditText(this).apply {
                hint = "Search courses"
                contentDescription = "Search courses"
                setSingleLine(true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setTextColor(ink)
                setHintTextColor(quietInk)
                backgroundTintList = android.content.res.ColorStateList.valueOf(line)
            }
            column.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
            val filter = Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(
                    this@StudyActivityLibraryUi,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("All courses", "In progress", "Ready to start"),
                )
            }
            if (showInProgress) filter.setSelection(1)
            column.addView(filter)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(list)
            fun render() {
                list.removeAllViews()
                val term = search.text.toString().trim().lowercase()
                val activeRevisions = library.sessions.filter {
                    it.status == SessionStatus.ACTIVE || it.status == SessionStatus.PAUSED
                }.mapNotNull { it.revision }.toSet()
                val courses = library.courses.filter { item ->
                    val course = item.course
                    val matches = course?.title.orEmpty().lowercase().contains(term) ||
                        item.record.packageId.lowercase().contains(term)
                    val ongoing = course?.units?.any { it.revision in activeRevisions } == true
                    val stateMatches = when (filter.selectedItemPosition) {
                        1 -> ongoing
                        2 -> !ongoing
                        else -> true
                    }
                    matches && stateMatches
                }
                list.addView(TextView(this).apply {
                    text = "${courses.size} courses"
                    textSize = 13f
                    setTextColor(quietInk)
                    setPadding(0, dp(10), 0, dp(10))
                })
                if (courses.isEmpty()) list.addView(TextView(this).apply {
                    text = "No matching courses. Import a course package to get started."
                    textSize = 15f
                    setTextColor(ink)
                    setPadding(0, dp(8), 0, dp(8))
                })
                courses.forEach { item ->
                    val course = item.course
                    catalogRow(
                        course?.title ?: "Missing course",
                        "${item.record.packageVersion} · ${if (course == null) "Reimport this revision to resume" else "${course.units.size} units"}",
                        list,
                    ) { course?.let(::course) }
                }
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                override fun afterTextChanged(s: Editable?) = Unit
            })
            filter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            }
            render()
            if (library.sessions.isNotEmpty()) {
                heading("Study history")
                library.sessions.forEach { entry ->
                    val revision = entry.revision?.packageVersion
                    val state = entry.status?.name?.lowercase() ?: "missing course"
                    catalogRow(entry.title, "$revision · $state") { history(entry) }
                }
            }
            heading("Notes")
            action("Open notes") { notes() }
            heading("Learner data")
            action("Export learner backup") {
                confirm(
                    "Export personal data?",
                    "The backup is readable JSON containing proofs, drafts and notes. Choose where it will be saved.",
                    "Choose destination",
                ) { pick(EXPORT_DOCUMENT_REQUEST) }
            }
            action("Restore learner backup") {
                confirm(
                    "Restore learner backup?",
                    "Restore requires an empty learner profile. Course files are imported separately.",
                    "Choose backup",
                ) { pick(RESTORE_DOCUMENT_REQUEST) }
            }
        }
    }

    protected fun reviewScreen() {
        work({ library() }) { library ->
            current = null
            screen("Review", "Review")
            label("Return to earlier ideas through fresh questions.", 15f)
            if (library.reviews.isEmpty()) {
                heading("No reviews due today", 23f)
                label("When a check is due, you’ll see what it covers and why it was scheduled.", 15f)
                textAction("Back to Home", ::home)
            }
            library.reviews.forEach { review ->
                val objective = review.unit.text.path("objectives").firstOrNull {
                    it.path("id").asText() == review.task.objectiveId
                }?.path("description")?.asText().orEmpty()
                val detail = buildString {
                    append(objective)
                    append(" · Due ${review.task.dueDate}")
                    if (review.freshActivity == null) append(" · Fresh check unavailable; practice this unit")
                }
                catalogRow(review.unit.title, detail) {
                    if (review.freshActivity == null) {
                        work({ start(review.unit) }, success = ::showStudy)
                    } else {
                        work({ start(review.unit, review.freshActivity) }, success = ::showStudy)
                    }
                }
            }
        }
    }

    protected fun course(course: Course) {
        screen(course.title, backLabel = "Library") { libraryScreen() }
        label("A focused course for independent mathematical reasoning.", 15f)
        label("Version ${course.descriptor.version} · Review claim: ${course.review}")
        content(course.conventions)
        label(
            course.manifest
                .path("review")
                .path("note")
                .asText(),
        )
        val resumeSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(resumeSlot)
        work({
            val revisions = course.units.map { it.revision }.toSet()
            library().sessions.firstOrNull {
                it.revision in revisions && (it.status == SessionStatus.ACTIVE || it.status == SessionStatus.PAUSED)
            }
        }) { session ->
            if (session != null) {
                primaryAction("Resume current unit", resumeSlot) {
                    work({ resume(session.id) }, success = ::showStudy)
                }
            }
        }
        heading("Course units")
        val unitSearch = EditText(this).apply {
            hint = "Search units"
            contentDescription = "Search units"
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            backgroundTintList = android.content.res.ColorStateList.valueOf(line)
        }
        column.addView(unitSearch, LinearLayout.LayoutParams(-1, dp(52)))
        val unitCount = label("${course.units.size} units", 13f)
        val units = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(units)
        fun renderUnits() {
            units.removeAllViews()
            val term = unitSearch.text.toString().trim().lowercase()
            val matching = course.units.filter { it.title.lowercase().contains(term) }
            unitCount.text = "${matching.size} of ${course.units.size} units"
            if (matching.isEmpty()) {
                units.addView(TextView(this).apply {
                    text = "No matching units. Try another title."
                    textSize = 15f
                    setTextColor(ink)
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            matching.forEach { unit ->
                val prerequisites = unit.text.path("requires_concepts").map { it.asText() }
                val detail = if (prerequisites.isEmpty()) "Start unit" else "Assumed knowledge: ${prerequisites.joinToString()}"
                catalogRow(unit.title, detail, units) { work({ start(unit) }, success = ::showStudy) }
            }
        }
        unitSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderUnits()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderUnits()
        action("Remove this revision from library") {
            confirm("Remove from library?", "Existing attempts, notes and pinned sessions remain available.", "Remove") {
                work({
                    store.setPackageActive(course.descriptor.packageId, course.descriptor.version, false)
                }) { home() }
            }
        }
    }

    protected fun confirmImport(preview: ImportPreview) {
        pendingImport = preview
        val course = preview.course
        val prerequisites = course.units.flatMap { it.text.path("requires_concepts").map { item -> item.asText() } }.distinct()
        val versions = preview.existing.filter { it.active }.joinToString { it.packageVersion }
        screen("Import course", backLabel = "Library") {
            pendingImport = null
            work({ discard(preview) }) { libraryScreen() }
        }
        heading(course.title, 25f)
        label("Version ${course.descriptor.version} · ${course.units.size} units", 13f)
        heading("Review status", 17f)
        label("${course.review.replace('_', ' ').replaceFirstChar { it.uppercase() }}. ${course.manifest.path("review").path("note").asText()}")
        heading("Conventions", 17f)
        content(course.conventions)
        heading("Assumed knowledge", 17f)
        label(if (prerequisites.isEmpty()) "No prerequisites listed." else prerequisites.joinToString())
        if (versions.isNotEmpty()) {
            heading("Installed versions", 17f)
            label("$versions. You can start work on either revision. In-progress sessions stay pinned.")
        }
        footerAction("Import course") {
            pendingImport = null
            work({ install(preview) }) { home() }
        }
        action(if (versions.isEmpty()) "Cancel" else "Keep existing") {
            pendingImport = null
            work({ discard(preview) }) { libraryScreen() }
        }
    }

    protected fun history(entry: SessionEntry) {
        work({ store.attemptHistory(entry.id) }) { attempts ->
            screen(entry.title, backLabel = "Home", onBack = ::home)
            if (entry.status == SessionStatus.ACTIVE || entry.status == SessionStatus.PAUSED) {
                action("Continue existing work") { work({ resume(entry.id) }, success = ::showStudy) }
            }
            if (entry.status == null) label("Reimport this exact course revision to resume.")
            heading("Attempts")
            if (attempts.isEmpty()) label("No submitted attempts.")
            attempts.forEach { attempt ->
                heading("${attempt.method.replace('_', ' ')} · ${attempt.outcome}")
                content(attempt.contextSnapshot)
                content(attempt.response)
                val assistance = org.json.JSONObject(attempt.assistance.toString(Charsets.UTF_8))
                label(
                    "Hints opened: ${assistance.optJSONArray("revealed_hints")?.length() ?: 0}. " +
                        "Solution opened: ${if (assistance.optBoolean("solution_revealed")) "yes" else "no"}.",
                )
            }
        }
    }

    protected fun notes(anchor: String? = null) {
        work({ store.notes(anchor) }) { notes ->
            screen("Notes", backLabel = "Home", onBack = ::home)
            if (notes.isEmpty()) label("No notes yet.")
            notes.forEach { note ->
                heading(note.contentId)
                content(note.text)
                action("Edit note") { editNote(note) }
            }
            action("New note") {
                val now = Instant.now().toString()
                editNote(StoredNote(newId(), anchor ?: "personal", null, "notes", "", now, now))
            }
        }
    }

    protected fun editNote(note: StoredNote) {
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

}
