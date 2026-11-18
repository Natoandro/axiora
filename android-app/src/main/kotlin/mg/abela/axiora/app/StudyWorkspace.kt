package mg.abela.axiora.app

import android.content.Context
import android.net.Uri
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.LearningUnit
import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import mg.abela.axiora.learning.EvidencePolicy
import mg.abela.axiora.learning.ReviewScheduler
import mg.abela.axiora.learning.ReviewTask
import mg.abela.axiora.learning.SessionStatus
import mg.abela.axiora.learning.StudyCommand
import mg.abela.axiora.learning.StudyEngine
import mg.abela.axiora.learning.StudySession
import mg.abela.axiora.learning.TransitionResult
import mg.abela.axiora.learning.UnitLookup
import mg.abela.axiora.learning.UnitRevisionRef
import mg.abela.axiora.learning.ZoneSource
import mg.abela.axiora.storage.BinaryStudySessionCodec
import mg.abela.axiora.storage.BinaryTransitionResultCodec
import mg.abela.axiora.storage.InstalledPackage
import mg.abela.axiora.storage.PackageInstaller
import mg.abela.axiora.storage.SqliteLearnerStore
import mg.abela.axiora.storage.StudyRepository
import mg.abela.axiora.storage.android.AndroidLearnerSqliteDriver
import mg.abela.axiora.validation.PackageArchiveReader
import mg.abela.axiora.validation.PackageLimits
import mg.abela.axiora.validation.PackageRevisionDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal fun newId(): String = UUID.randomUUID().toString()

internal data class CourseUnit(
    val revision: UnitRevisionRef,
    val model: LearningUnit,
    val text: JsonNode,
) {
    val title: String get() = text.path("title").asText()

    fun activity(id: String): JsonNode = text.path("activities").path(id)
}

internal data class Course(
    val descriptor: PackageRevisionDescriptor,
    val manifest: JsonNode,
    val units: List<CourseUnit>,
) {
    val title: String get() = manifest.path("title").asText()
    val review: String get() = manifest.path("review").path("status").asText()
    val conventions: String get() = manifest.path("conventions").asText()
}

internal data class ImportPreview(
    val staged: Path,
    val course: Course,
    val existing: List<InstalledPackage>,
)

internal data class LibraryCourse(
    val record: InstalledPackage,
    val course: Course?,
)

internal data class SessionEntry(
    val id: String,
    val title: String,
    val revision: UnitRevisionRef?,
    val status: SessionStatus?,
)

internal data class DueReview(
    val task: ReviewTask,
    val unit: CourseUnit,
    val freshActivity: String?,
)

internal data class Library(
    val courses: List<LibraryCourse>,
    val sessions: List<SessionEntry>,
    val reviews: List<DueReview>,
)

internal data class StudyView(
    val session: StudySession,
    val unit: CourseUnit,
)

/** All calls are serialized by the application worker. */
internal class StudyWorkspace(
    private val context: Context,
) {
    private val mapper = ObjectMapper()
    private val reader = PackageArchiveReader()
    private val driver = AndroidLearnerSqliteDriver(context.getDatabasePath("learner.db"))
    val store = SqliteLearnerStore(driver)
    private val installer = PackageInstaller(context.filesDir.toPath(), store)
    private val incoming = context.cacheDir.toPath().resolve("incoming.axp")
    private var resolved: Pair<InstalledPackage, Course>? = null
    private val lookup = UnitLookup { revision -> unit(revision) ?.model }
    private val engine = StudyEngine(lookup, zone = ZoneSource { ZoneId.systemDefault().rules.getOffset(it) })
    private val sessions = BinaryStudySessionCodec(lookup)
    private val results = BinaryTransitionResultCodec(sessions)
    private val repository =
        StudyRepository(store, engine, sessions, results, { session, activityId ->
            val activity = checkNotNull(unit(session.revision)).activity(activityId)
            buildString {
                appendLine(activity.path("prompt_md").asText())
                activity.path("options").forEach { appendLine("${it.path("id").asText()} — ${it.path("text_md").asText()}") }
                activity
                    .path(
                        "evaluator",
                    ).path("expected")
                    .takeUnless { it.isMissingNode }
                    ?.let { appendLine("Expected option: ${it.asText()}") }
                activity.path("rubric").forEach { appendLine(it.path("criterion_md").asText()) }
            }.trim().take(2_000_000)
        })

    init {
        installer.reconcile()
        Files.deleteIfExists(incoming)
    }

    private fun readCourse(path: Path): Course =
        reader.load(path) { descriptor, staged, validated ->
            val manifest = mapper.readTree(staged.resolve("manifest.json").toFile())
            val models = validated.units.associateBy { it.id.value }
            val units =
                manifest.path("units").map { record ->
                    val model = models.getValue(record.path("id").asText())
                    val revision =
                        UnitRevisionRef(
                            StableId(descriptor.packageId),
                            requireNotNull(Revision.parse(descriptor.version)),
                            model.id,
                            model.version,
                        )
                    CourseUnit(revision, model, mapper.readTree(staged.resolve(record.path("path").asText()).toFile()))
                }
            Course(descriptor, manifest, units)
        }

    fun preview(uri: Uri): ImportPreview {
        try {
            Files.newOutputStream(incoming).use { output -> fromUri(uri) { copyLimited(it, output, PackageLimits.ARCHIVE_BYTES) } }
            val course = readCourse(incoming)
            val existing = store.packageRevisions().filter { it.packageId == course.descriptor.packageId }
            require(
                existing.none { it.packageVersion == course.descriptor.version && it.manifestSha256 != course.descriptor.manifestSha256 },
            ) {
                "This course ID and version already belong to different course content."
            }
            return ImportPreview(incoming, course, existing)
        } catch (error: Exception) {
            Files.deleteIfExists(incoming)
            throw error
        }
    }

    fun install(preview: ImportPreview) {
        installer.install(preview.staged)
        Files.deleteIfExists(preview.staged)
    }

    fun discard(preview: ImportPreview) {
        Files.deleteIfExists(preview.staged)
    }

    private fun resolve(record: InstalledPackage): Course? {
        val path =
            context.filesDir
                .toPath()
                .resolve("packages")
                .resolve(record.packageId)
                .resolve(record.packageVersion)
                .resolve("content.axp")
                .normalize()
        if (path != Paths.get(record.contentLocation) || !Files.isRegularFile(path)) return null
        resolved?.takeIf { it.first == record }?.let { return it.second }
        val course = readCourse(path)
        check(course.descriptor.manifestSha256 == record.manifestSha256) { "The installed course differs from its saved revision." }
        resolved = record to course
        return course
    }

    private fun unit(revision: UnitRevisionRef): CourseUnit? {
        val record =
            store.packageRevisions().find {
                it.packageId == revision.packageId.value && it.packageVersion == revision.packageVersion.toString()
            } ?: return null
        return resolve(record)?.units?.find { it.revision == revision }
    }

    fun library(): Library {
        val courses = store.activePackages().map { LibraryCourse(it, resolve(it)) }
        val loaded = mutableListOf<StudySession>()
        val entries =
            store.sessionIds().map { id ->
                val record = checkNotNull(store.loadSession(id))
                val unit = unit(record.revision)
                if (unit == null) {
                    SessionEntry(id, record.revision.unitId.value, record.revision, null)
                } else {
                    loaded += checkNotNull(repository.load(id))
                    SessionEntry(id, unit.title, unit.revision, SessionStatus.valueOf(record.status))
                }
            }
        val observations =
            loaded
                .flatMap { session ->
                    EvidencePolicy.observations(session).map { it.attemptId to (session.revision to it) }
                }.toMap()
        val tasks = mutableMapOf<Pair<UnitRevisionRef, String>, ReviewTask>()
        var latest: java.time.Instant? = null
        store.submissionOrder().forEach { attempt ->
            val (revision, observation) = observations[attempt] ?: return@forEach
            val coherent = latest?.let { !observation.submittedAt.isBefore(it) } ?: true
            if (coherent) latest = observation.submittedAt
            val key = revision to observation.objectiveId
            ReviewScheduler.apply(tasks[key], observation, revision, coherent)?.let { tasks[key] = it }
        }
        val activeUnits = courses.flatMap { it.course?.units.orEmpty() }.associateBy { it.revision }
        val reviews =
            ReviewScheduler.deduplicate(tasks.values.toList()).filter { !it.dueDate.isAfter(LocalDate.now()) }.mapNotNull { task ->
                val unit = activeUnits[task.unitRevision] ?: return@mapNotNull null
                val fresh =
                    unit.model.activities.entries
                        .firstOrNull { (_, activity) ->
                            val evidence = (activity as? Choice)?.evidence
                            evidence != null && evidence.first == task.objectiveId && evidence.second != task.lastAssessmentFamily &&
                                observations.values.none { (revision, observation) ->
                                    revision == unit.revision && observation.assessmentFamily == evidence.second
                                }
                        }?.key
                DueReview(task, unit, fresh)
            }
        return Library(courses, entries, reviews)
    }

    fun start(
        unit: CourseUnit,
        reviewActivity: String? = null,
    ): StudyView {
        check(
            store.activePackages().any {
                it.packageId == unit.revision.packageId.value && it.packageVersion == unit.revision.packageVersion.toString()
            },
        ) { "This course is unavailable for new work." }
        val started = engine.startSession(newId(), unit.revision, reviewActivity).getOrThrow()
        repository.create(started)
        return StudyView(started, unit)
    }

    fun resume(id: String): StudyView {
        val session = checkNotNull(repository.load(id)) { "This study session is unavailable." }
        val view = StudyView(session, checkNotNull(unit(session.revision)))
        return if (session.status == SessionStatus.PAUSED) send(view, StudyCommand.Resume(newId())) else view
    }

    fun send(
        view: StudyView,
        command: StudyCommand,
    ): StudyView {
        val receipt = repository.handle(view.session.id, command)
        return when (val result = results.decode(receipt.resultVersion, receipt.resultPayload)) {
            is TransitionResult.Accepted -> view.copy(session = result.session)
            is TransitionResult.Rejected -> error("This action could not be saved (${result.error.javaClass.simpleName}).")
        }
    }

    fun flushDraft(
        previous: StudyView,
        text: String,
    ): StudyView? {
        val saved = repository.load(previous.session.id) ?: return null
        if (saved.currentActivityId != previous.session.currentActivityId || saved.status != SessionStatus.ACTIVE) return null
        val view = previous.copy(session = saved)
        val progress = saved.activityProgress()
        return if (progress.draft == text) view else send(view, StudyCommand.SaveProofDraft(newId(), text, progress.draftRevision + 1))
    }

    fun export(uri: Uri) {
        val bytes = store.exportSnapshot()
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "The selected destination could not be opened." }
            .use { it.write(bytes) }
    }

    fun restore(uri: Uri): Int {
        val bytes = java.io.ByteArrayOutputStream()
        fromUri(uri) { copyLimited(it, bytes, 64 * 1024 * 1024) }
        return store.restoreSnapshot(bytes.toByteArray()).size
    }

    private fun <T> fromUri(
        uri: Uri,
        read: (InputStream) -> T,
    ): T = requireNotNull(context.contentResolver.openInputStream(uri)) { "The selected document could not be opened." }.use(read)

    private fun copyLimited(
        input: InputStream,
        output: OutputStream,
        limit: Int,
    ) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return
            total += count
            require(total <= limit) { "The selected file exceeds the supported size." }
            output.write(buffer, 0, count)
        }
    }
}
