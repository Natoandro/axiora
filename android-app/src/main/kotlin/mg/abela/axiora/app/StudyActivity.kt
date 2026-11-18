package mg.abela.axiora.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import mg.abela.axiora.learning.StudyCommand
import java.util.concurrent.Executors

internal class StudyActivity : StudyActivityLessonUi() {
    protected override val main = Handler(Looper.getMainLooper())
    override var current: StudyView? = null
    protected override var draftSequence = 0L
    private var busy = false
    private var stopped = false
    protected override val autosave = Runnable { saveDraft(null) }
    override val font by lazy { runCatching { assets.open("math.otf").use { it.readBytes() } }.getOrNull() }
    override val uiBusy: Boolean get() = busy

    override fun navigateHome() = home()

    override fun navigateLibrary() = libraryScreen()

    override fun navigateReview() = reviewScreen()

    override fun showStudy(view: StudyView) = study(view)

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
            saveDraft(null)
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
            IMPORT_DOCUMENT_REQUEST -> {
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

            EXPORT_DOCUMENT_REQUEST -> {
                work({ export(uri) }) { notice("Backup saved", "Your learner records were written to the selected file.") }
            }

            RESTORE_DOCUMENT_REQUEST -> {
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
        if (pendingImport != null) {
            val preview = pendingImport ?: return
            pendingImport = null
            work({ discard(preview) }) { libraryScreen() }
        } else if (current != null) {
            saveDraft(::home)
        } else {
            super.onBackPressed()
        }
    }

    protected override fun saveDraft(after: (() -> Unit)?) {
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

    protected override fun send(
        command: StudyCommand,
        then: (() -> Unit)?,
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

    protected override fun <T> work(
        operation: StudyWorkspace.() -> T,
        failure: (Throwable) -> Unit,
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

    protected override fun showError(error: Throwable) {
        if (error is LinkageError) android.util.Log.e("Axiora", "Android runtime incompatibility", error)
        val message =
            if (error is IllegalArgumentException || error is IllegalStateException) {
                error.message?.take(900) ?: "Try the action again."
            } else {
                "Check that the selected file and local storage are available, then try again."
            }
        notice("Action could not be completed", message)
    }

    protected override fun confirm(
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

    protected override fun pick(operation: Int) {
        val intent =
            Intent(if (operation == EXPORT_DOCUMENT_REQUEST) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .setType(if (operation == EXPORT_DOCUMENT_REQUEST) "application/json" else "*/*")
        if (operation == EXPORT_DOCUMENT_REQUEST) intent.putExtra(Intent.EXTRA_TITLE, "axiora-learner-backup.json")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, operation)
        } catch (_: android.content.ActivityNotFoundException) {
            notice("File picker unavailable", "This device has no system document picker.")
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
        var sharedWorkspace: StudyWorkspace? = null
    }
}
