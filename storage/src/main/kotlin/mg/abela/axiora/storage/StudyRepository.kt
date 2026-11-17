package mg.abela.axiora.storage

import com.fasterxml.jackson.databind.ObjectMapper
import mg.abela.axiora.learning.AssistanceSnapshot
import mg.abela.axiora.learning.EventType
import mg.abela.axiora.learning.EvidencePolicy
import mg.abela.axiora.learning.LearningEvent
import mg.abela.axiora.learning.StudyCommand
import mg.abela.axiora.learning.StudyEngine
import mg.abela.axiora.learning.StudySession
import mg.abela.axiora.learning.TransitionResult
import java.nio.ByteBuffer
import java.time.Instant

/** Application-owned codec boundary; payload versions are stored beside every durable value. */
interface VersionedStudyCodec<T> {
    val version: Int

    fun encode(value: T): ByteArray

    fun decode(
        version: Int,
        bytes: ByteArray,
    ): T
}

class StudyRepository(
    private val store: SqliteLearnerStore,
    private val engine: StudyEngine,
    private val sessions: VersionedStudyCodec<StudySession>,
    private val results: VersionedStudyCodec<TransitionResult>,
    private val now: () -> Instant = Instant::now,
) {
    fun create(session: StudySession) {
        require(sessions.version > 0) { "Session codec version must be positive." }
        val checkpoint = encodeSession(session)
        val startEvents = session.events.filter { it.type == EventType.SESSION_STARTED }
        require(startEvents.size <= 1) { "A session can contain at most one start event." }
        val initialEvent = startEvents.singleOrNull()?.let(::eventWrite)
        store.createSession(
            StoredSession(
                session.id,
                session.revision,
                session.status.name,
                session.currentActivityId,
                checkpoint,
                now().toString(),
            ),
            initialEvent,
            if (initialEvent != null) sessions.version else null,
            if (initialEvent != null) checkpoint else null,
        )
    }

    fun load(sessionId: String): StudySession? = store.loadSession(sessionId)?.let { stored -> decodeSession(stored.checkpoint) }

    /** The reducer runs only after durable command-receipt lookup, inside the same SQLite transaction. */
    fun handle(
        sessionId: String,
        command: StudyCommand,
    ): CommandReceipt =
        store.executeCommand(sessionId, command.commandId) {
            val stored =
                store.loadSession(sessionId)
                    ?: throw IllegalArgumentException("Session '$sessionId' is unavailable.")
            val before = decodeSession(stored.checkpoint)
            val transition = engine.handle(before, command)
            val updated =
                when (transition) {
                    is TransitionResult.Accepted -> transition.session
                    is TransitionResult.Rejected -> transition.session
                }
            val resultBytes = results.encode(transition)
            val accepted = transition as? TransitionResult.Accepted
            val newAttemptIds =
                accepted
                    ?.newAttempts
                    .orEmpty()
                    .map { it.id }
                    .toSet()
            val observations = EvidencePolicy.observations(updated).filter { it.attemptId in newAttemptIds }
            CommandCommit(
                sessionId = sessionId,
                commandId = command.commandId,
                resultVersion = results.version,
                resultPayload = resultBytes,
                sessionStatus = updated.status.name,
                currentActivityId = updated.currentActivityId,
                checkpoint = encodeSession(updated),
                updatedAt = now().toString(),
                unitRevisionKey = revisionKey(updated),
                events = accepted?.newEvents.orEmpty().map(::eventWrite),
                attempts =
                    accepted?.newAttempts.orEmpty().map { attempt ->
                        AttemptWrite(
                            attempt.id,
                            attempt.commandId,
                            revisionKey(updated),
                            attempt.activityId,
                            attempt.sequence,
                            attempt.response,
                            attempt.outcome.name.lowercase(),
                            attempt.method.name.lowercase(),
                            assistanceBytes(attempt.assistance),
                            attempt.submittedAt.toString(),
                            attempt.localStudyDate.toString(),
                            attempt.zoneOffset.toString(),
                        )
                    },
                observations =
                    observations.map { observation ->
                        ObservationWrite(
                            observation.attemptId,
                            observation.objectiveId,
                            observation.skill,
                            observation.assessmentFamily,
                            observation.policyVersion,
                        )
                    },
                invalidatedObjectives = observations.map { it.objectiveId }.toSet(),
                draft =
                    (command as? StudyCommand.SaveProofDraft)?.let {
                        DraftWrite(updated.currentActivityId, it.text, it.revision, now().toString())
                    },
            )
        }

    private fun revisionKey(session: StudySession): String {
        val revision = session.revision
        return "${revision.packageId.value}@${revision.packageVersion}/${revision.unitId.value}@${revision.unitVersion}"
    }

    private fun eventWrite(event: LearningEvent) =
        EventWrite(
            event.id,
            event.commandId,
            event.revision,
            event.type.name.lowercase(),
            event.payloadVersion,
            event.occurrence.toString(),
            event.activityId,
            payloadJson(event.payload),
        )

    private fun payloadJson(payload: Map<String, String>): ByteArray = ObjectMapper().writeValueAsBytes(payload)

    private fun assistanceBytes(assistance: AssistanceSnapshot): ByteArray =
        ObjectMapper().writeValueAsBytes(
            mapOf(
                "revealed_hints" to assistance.revealedHints.sorted(),
                "solution_revealed" to assistance.solutionRevealed,
            ),
        )

    private fun encodeSession(session: StudySession): ByteArray {
        require(sessions.version > 0) { "Session codec version must be positive." }
        val payload = sessions.encode(session)
        return ByteBuffer
            .allocate(Int.SIZE_BYTES + payload.size)
            .putInt(sessions.version)
            .put(payload)
            .array()
    }

    private fun decodeSession(bytes: ByteArray): StudySession {
        require(bytes.size >= Int.SIZE_BYTES) { "Session checkpoint is truncated." }
        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.int
        require(version > 0) { "Session checkpoint version is invalid." }
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        return sessions.decode(version, payload)
    }
}
