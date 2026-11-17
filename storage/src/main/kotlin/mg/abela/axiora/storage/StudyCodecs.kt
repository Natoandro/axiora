package mg.abela.axiora.storage

import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import mg.abela.axiora.learning.ActivityProgress
import mg.abela.axiora.learning.AssessmentMethod
import mg.abela.axiora.learning.AssistanceSnapshot
import mg.abela.axiora.learning.Attempt
import mg.abela.axiora.learning.AttemptOutcome
import mg.abela.axiora.learning.EventType
import mg.abela.axiora.learning.LearningEvent
import mg.abela.axiora.learning.RubricMark
import mg.abela.axiora.learning.SessionStatus
import mg.abela.axiora.learning.StudySession
import mg.abela.axiora.learning.TransitionError
import mg.abela.axiora.learning.TransitionResult
import mg.abela.axiora.learning.UnitLookup
import mg.abela.axiora.learning.UnitRevisionRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Deterministic bounded codec for engine state; content is reloaded by its exact pinned revision. */
class BinaryStudySessionCodec(
    private val units: UnitLookup,
) : VersionedStudyCodec<StudySession> {
    override val version = 1

    override fun encode(value: StudySession): ByteArray =
        payload { out ->
            out.string(value.id)
            out.revision(value.revision)
            out.string(value.status.name)
            out.string(value.currentActivityId)
            out.count(value.progress.size)
            value.progress.toSortedMap().forEach { (activityId, progress) ->
                out.string(activityId)
                out.progress(progress)
            }
            out.count(value.events.size)
            value.events.forEach { event -> out.event(event) }
            out.count(value.consumedCommandIds.size)
            value.consumedCommandIds.sorted().forEach(out::string)
        }.also { require(it.size <= MAX_PAYLOAD_BYTES) { "Study checkpoint exceeds the supported size limit." } }

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): StudySession {
        require(version == this.version) { "Unsupported study checkpoint version: $version." }
        return parse(bytes) { input ->
            val id = input.string()
            val revision = input.revision()
            val unit = units.find(revision) ?: throw IllegalArgumentException("Pinned unit revision is unavailable.")
            require(unit.id == revision.unitId && unit.version == revision.unitVersion) {
                "Resolved unit does not match its pinned revision."
            }
            val status = enumValue<SessionStatus>(input.string())
            val current = input.string()
            val progress =
                buildMap {
                    repeat(input.count()) {
                        val activityId = input.string()
                        require(put(activityId, input.progress()) == null) { "Checkpoint has duplicate activity state." }
                    }
                }
            val events = List(input.count()) { input.event(id) }
            val commands =
                buildSet {
                    repeat(input.count()) {
                        require(add(input.string())) { "Checkpoint has duplicate command IDs." }
                    }
                }
            require(current in unit.activities) { "Checkpoint points to an unknown activity." }
            require(progress.keys.all { it in unit.activities }) { "Checkpoint contains unknown activity state." }
            StudySession(id, revision, unit, status, current, progress, events, commands)
        }
    }
}

/** Replay result codec stores only the result kind and IDs; primary state remains in the checkpoint. */
class BinaryTransitionResultCodec(
    private val sessions: VersionedStudyCodec<StudySession>,
) : VersionedStudyCodec<TransitionResult> {
    override val version = 1

    override fun encode(value: TransitionResult): ByteArray =
        payload { out ->
            when (value) {
                is TransitionResult.Accepted -> {
                    out.writeByte(1)
                    out.bytes(sessions.encode(value.session))
                    out.strings(value.newEvents.map { it.id })
                    out.strings(value.newAttempts.map { it.id })
                }

                is TransitionResult.Rejected -> {
                    out.writeByte(2)
                    out.bytes(sessions.encode(value.session))
                    out.string(errorCode(value.error))
                }
            }
        }.also { require(it.size <= MAX_PAYLOAD_BYTES) { "Command result exceeds the supported size limit." } }

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): TransitionResult {
        require(version == this.version) { "Unsupported command result version: $version." }
        return parse(bytes) { input ->
            when (input.readUnsignedByte()) {
                1 -> {
                    val session = sessions.decode(sessions.version, input.bytes())
                    val eventIds = input.strings().toSet()
                    val attemptIds = input.strings().toSet()
                    val events = session.events.filter { it.id in eventIds }
                    val attempts =
                        session.progress.values
                            .flatMap { it.attempts }
                            .filter { it.id in attemptIds }
                    require(events.size == eventIds.size && attempts.size == attemptIds.size) {
                        "Command result references missing session records."
                    }
                    TransitionResult.Accepted(session, events, attempts)
                }

                2 -> {
                    val session = sessions.decode(sessions.version, input.bytes())
                    TransitionResult.Rejected(session, errorFromCode(input.string()))
                }

                else -> {
                    throw IllegalArgumentException("Command result kind is invalid.")
                }
            }
        }
    }

    private fun errorCode(error: TransitionError): String =
        when (error) {
            TransitionError.SessionNotActive -> "session_not_active"
            TransitionError.SessionNotPaused -> "session_not_paused"
            TransitionError.DuplicateCommand -> "duplicate_command"
            TransitionError.WrongActivityType -> "wrong_activity_type"
            TransitionError.InvalidSelection -> "invalid_selection"
            TransitionError.NoSelection -> "no_selection"
            TransitionError.RetryNotAvailable -> "retry_not_available"
            TransitionError.InvalidHint -> "invalid_hint"
            TransitionError.StaleDraftRevision -> "stale_draft_revision"
            TransitionError.ProofNotSubmitted -> "proof_not_submitted"
            TransitionError.InvalidRubric -> "invalid_rubric"
            TransitionError.ActivityNotReady -> "activity_not_ready"
            TransitionError.AmbiguousChoiceSkip -> "ambiguous_choice_skip"
        }

    private fun errorFromCode(code: String): TransitionError =
        when (code) {
            "session_not_active" -> TransitionError.SessionNotActive
            "session_not_paused" -> TransitionError.SessionNotPaused
            "duplicate_command" -> TransitionError.DuplicateCommand
            "wrong_activity_type" -> TransitionError.WrongActivityType
            "invalid_selection" -> TransitionError.InvalidSelection
            "no_selection" -> TransitionError.NoSelection
            "retry_not_available" -> TransitionError.RetryNotAvailable
            "invalid_hint" -> TransitionError.InvalidHint
            "stale_draft_revision" -> TransitionError.StaleDraftRevision
            "proof_not_submitted" -> TransitionError.ProofNotSubmitted
            "invalid_rubric" -> TransitionError.InvalidRubric
            "activity_not_ready" -> TransitionError.ActivityNotReady
            "ambiguous_choice_skip" -> TransitionError.AmbiguousChoiceSkip
            else -> throw IllegalArgumentException("Unknown transition error code.")
        }
}

private fun DataOutputStream.progress(value: ActivityProgress) {
    nullableString(value.selectedOption)
    nullableBoolean(value.firstChoiceBranch)
    count(value.attempts.size)
    value.attempts.forEach(::attempt)
    writeBoolean(value.feedbackVisible)
    string(value.draft)
    writeLong(value.draftRevision)
    assistance(value.assistance)
    writeBoolean(value.proofSubmitted)
    count(value.rubric.size)
    value.rubric.toSortedMap().forEach { (key, mark) ->
        string(key)
        string(mark.name)
    }
    nullableString(value.reflection)
    writeBoolean(value.skipped)
    writeBoolean(value.readyToContinue)
}

private fun DataInputStream.progress(): ActivityProgress {
    val selected = nullableString()
    val branch = nullableBoolean()
    val attempts = List(count()) { attempt() }
    val feedback = readBoolean()
    val draft = string()
    val draftRevision = readLong()
    val assistance = assistance()
    val submitted = readBoolean()
    val rubricCount = count()
    val rubric =
        buildMap {
            repeat(rubricCount) {
                val key = string()
                val mark = enumValue<RubricMark>(string())
                require(put(key, mark) == null) { "Checkpoint has duplicate rubric marks." }
            }
        }
    val reflection = nullableString()
    val skipped = readBoolean()
    val ready = readBoolean()
    return ActivityProgress(
        selected,
        branch,
        attempts,
        feedback,
        draft,
        draftRevision,
        assistance,
        submitted,
        rubric,
        reflection,
        skipped,
        ready,
    )
}

private fun DataOutputStream.attempt(value: Attempt) {
    string(value.id)
    string(value.commandId)
    string(value.activityId)
    writeInt(value.sequence)
    string(value.response)
    string(value.outcome.name)
    string(value.method.name)
    assistance(value.assistance)
    string(value.submittedAt.toString())
    string(value.localStudyDate.toString())
    string(value.zoneOffset.toString())
}

private fun DataInputStream.attempt() =
    Attempt(
        id = string(),
        commandId = string(),
        activityId = string(),
        sequence = readInt(),
        response = string(),
        outcome = enumValue<AttemptOutcome>(string()),
        method = enumValue<AssessmentMethod>(string()),
        assistance = assistance(),
        submittedAt = Instant.parse(string()),
        localStudyDate = LocalDate.parse(string()),
        zoneOffset = ZoneOffset.of(string()),
    )

private fun DataOutputStream.assistance(value: AssistanceSnapshot) {
    count(value.revealedHints.size)
    value.revealedHints.sorted().forEach { string(it) }
    writeBoolean(value.solutionRevealed)
}

private fun DataInputStream.assistance(): AssistanceSnapshot {
    val hintCount = count()
    val hints =
        buildSet {
            repeat(hintCount) {
                require(add(string())) { "Checkpoint has duplicate hints." }
            }
        }
    return AssistanceSnapshot(hints, readBoolean())
}

private fun DataOutputStream.event(value: LearningEvent) {
    string(value.id)
    string(value.commandId)
    string(value.sessionId)
    revision(value.revision)
    string(value.type.name)
    string(value.occurrence.toString())
    nullableString(value.activityId)
    writeInt(value.payloadVersion)
    count(value.payload.size)
    value.payload.toSortedMap().forEach { (key, item) ->
        string(key)
        string(item)
    }
}

private fun DataInputStream.event(sessionId: String): LearningEvent {
    val id = string()
    val commandId = string()
    val savedSessionId = string()
    require(savedSessionId == sessionId) { "Event belongs to another session." }
    val revision = revision()
    val type = enumValue<EventType>(string())
    val occurrence = Instant.parse(string())
    val activity = nullableString()
    val payloadVersion = readInt()
    val payloadCount = count()
    val payload =
        buildMap {
            repeat(payloadCount) {
                val key = string()
                val value = string()
                require(put(key, value) == null) { "Event has duplicate payload keys." }
            }
        }
    return LearningEvent(id, commandId, savedSessionId, revision, type, occurrence, activity, payloadVersion, payload)
}

private fun DataOutputStream.revision(value: UnitRevisionRef) {
    string(value.packageId.value)
    string(value.packageVersion.toString())
    string(value.unitId.value)
    string(value.unitVersion.toString())
}

private fun DataInputStream.revision(): UnitRevisionRef =
    UnitRevisionRef(
        StableId(string()),
        requireNotNull(Revision.parse(string())) { "Checkpoint package version is invalid." },
        StableId(string()),
        requireNotNull(Revision.parse(string())) { "Checkpoint unit version is invalid." },
    )

private fun DataOutputStream.nullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) string(value)
}

private fun DataInputStream.nullableString(): String? = if (readBoolean()) string() else null

private fun DataOutputStream.nullableBoolean(value: Boolean?) {
    val encoded =
        when (value) {
            null -> 0
            false -> 1
            true -> 2
        }
    writeByte(encoded)
}

private fun DataInputStream.nullableBoolean(): Boolean? =
    when (readUnsignedByte()) {
        0 -> null
        1 -> false
        2 -> true
        else -> throw IllegalArgumentException("Checkpoint boolean is invalid.")
    }

private fun DataOutputStream.string(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_FIELD_BYTES)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.string(): String {
    val size = readInt()
    require(size in 0..MAX_FIELD_BYTES)
    val bytes = ByteArray(size)
    readFully(bytes)
    return Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

private fun DataOutputStream.strings(values: List<String>) {
    count(values.size)
    values.forEach { string(it) }
}

private fun DataInputStream.strings(): List<String> = List(count()) { string() }

private fun DataOutputStream.bytes(value: ByteArray) {
    require(value.size <= MAX_PAYLOAD_BYTES)
    writeInt(value.size)
    write(value)
}

private fun DataInputStream.bytes(): ByteArray {
    val size = readInt()
    require(size in 0..MAX_PAYLOAD_BYTES)
    val value = ByteArray(size)
    readFully(value)
    return value
}

private fun DataOutputStream.count(value: Int) {
    require(value in 0..MAX_COLLECTION_SIZE)
    writeInt(value)
}

private fun DataInputStream.count(): Int = readInt().also { require(it in 0..MAX_COLLECTION_SIZE) }

private inline fun <reified T : Enum<T>> enumValue(value: String): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("Checkpoint enum value is unsupported.")

private fun <T> payload(block: (DataOutputStream) -> T): ByteArray =
    ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use(block)
        bytes.toByteArray()
    }

private fun <T> parse(
    bytes: ByteArray,
    block: (DataInputStream) -> T,
): T {
    require(bytes.size <= MAX_PAYLOAD_BYTES) { "Study payload exceeds the supported size limit." }
    return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val result = block(input)
        require(input.available() == 0) { "Study payload has trailing bytes." }
        result
    }
}

private const val MAX_FIELD_BYTES = 2 * 1024 * 1024
private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
private const val MAX_COLLECTION_SIZE = 100_000
