package mg.abela.axiora.learning

import mg.abela.axiora.content.Activity
import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.End
import mg.abela.axiora.content.Exposition
import mg.abela.axiora.content.LearningUnit
import mg.abela.axiora.content.Proof
import mg.abela.axiora.content.Reflection
import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class UnitRevisionRef(
    val packageId: StableId,
    val packageVersion: Revision,
    val unitId: StableId,
    val unitVersion: Revision,
)

fun interface UnitLookup {
    fun find(revision: UnitRevisionRef): LearningUnit?
}

fun interface IdSource {
    fun next(): String
}

fun interface TimeSource {
    fun now(): Instant
}

fun interface ZoneSource {
    fun offsetAt(instant: Instant): ZoneOffset
}

enum class SessionStatus { ACTIVE, PAUSED, COMPLETED, ABANDONED }

enum class AttemptOutcome { CORRECT, INCORRECT, UNASSESSED }

enum class AssessmentMethod { EXACT_CHOICE, SELF_REVIEW }

enum class RubricMark { MET, NOT_MET, UNSURE }

enum class EventType {
    SESSION_STARTED,
    CHOICE_SUBMITTED,
    HINT_REVEALED,
    SOLUTION_REVEALED,
    PROOF_SUBMITTED,
    RUBRIC_RECORDED,
    ACTIVITY_SKIPPED,
    REFLECTION_RECORDED,
    SESSION_PAUSED,
    SESSION_RESUMED,
    SESSION_COMPLETED,
}

data class AssistanceSnapshot(
    val revealedHints: Set<String> = emptySet(),
    val solutionRevealed: Boolean = false,
) {
    val assisted: Boolean get() = revealedHints.isNotEmpty() || solutionRevealed
}

data class Attempt(
    val id: String,
    val commandId: String,
    val activityId: String,
    val sequence: Int,
    val response: String,
    val outcome: AttemptOutcome,
    val method: AssessmentMethod,
    val assistance: AssistanceSnapshot,
    val submittedAt: Instant,
    val localStudyDate: LocalDate,
    val zoneOffset: ZoneOffset,
)

data class LearningEvent(
    val id: String,
    val commandId: String,
    val sessionId: String,
    val revision: UnitRevisionRef,
    val type: EventType,
    val occurrence: Instant,
    val activityId: String?,
    val payloadVersion: Int = 1,
    val payload: Map<String, String> = emptyMap(),
)

data class ActivityProgress(
    val selectedOption: String? = null,
    val firstChoiceBranch: Boolean? = null,
    val attempts: List<Attempt> = emptyList(),
    val feedbackVisible: Boolean = false,
    val draft: String = "",
    val draftRevision: Long = 0,
    val assistance: AssistanceSnapshot = AssistanceSnapshot(),
    val proofSubmitted: Boolean = false,
    val rubric: Map<String, RubricMark> = emptyMap(),
    val reflection: String? = null,
    val skipped: Boolean = false,
    val readyToContinue: Boolean = false,
)

data class StudySession(
    val id: String,
    val revision: UnitRevisionRef,
    val unit: LearningUnit,
    val status: SessionStatus,
    val currentActivityId: String,
    val progress: Map<String, ActivityProgress> = emptyMap(),
    val events: List<LearningEvent> = emptyList(),
    val consumedCommandIds: Set<String> = emptySet(),
) {
    fun activityProgress(id: String = currentActivityId): ActivityProgress = progress[id] ?: ActivityProgress()
}

sealed interface StudyCommand {
    val commandId: String

    data class SelectChoice(
        override val commandId: String,
        val optionId: String,
    ) : StudyCommand

    data class SubmitChoice(
        override val commandId: String,
    ) : StudyCommand

    data class RevealHint(
        override val commandId: String,
        val hintId: String,
    ) : StudyCommand

    data class RevealSolution(
        override val commandId: String,
    ) : StudyCommand

    data class SaveProofDraft(
        override val commandId: String,
        val text: String,
        val revision: Long,
    ) : StudyCommand

    data class SubmitProof(
        override val commandId: String,
    ) : StudyCommand

    data class RecordRubric(
        override val commandId: String,
        val marks: Map<String, RubricMark>,
    ) : StudyCommand

    data class RecordReflection(
        override val commandId: String,
        val text: String,
    ) : StudyCommand

    data class SkipActivity(
        override val commandId: String,
    ) : StudyCommand

    data class Continue(
        override val commandId: String,
    ) : StudyCommand

    data class Pause(
        override val commandId: String,
    ) : StudyCommand

    data class Resume(
        override val commandId: String,
    ) : StudyCommand
}

sealed interface TransitionError {
    data object SessionNotActive : TransitionError

    data object SessionNotPaused : TransitionError

    data object DuplicateCommand : TransitionError

    data object WrongActivityType : TransitionError

    data object InvalidSelection : TransitionError

    data object NoSelection : TransitionError

    data object RetryNotAvailable : TransitionError

    data object InvalidHint : TransitionError

    data object StaleDraftRevision : TransitionError

    data object ProofNotSubmitted : TransitionError

    data object InvalidRubric : TransitionError

    data object ActivityNotReady : TransitionError

    data object AmbiguousChoiceSkip : TransitionError
}

sealed interface TransitionResult {
    data class Accepted(
        val session: StudySession,
        val newEvents: List<LearningEvent>,
        val newAttempts: List<Attempt>,
    ) : TransitionResult

    data class Rejected(
        val session: StudySession,
        val error: TransitionError,
    ) : TransitionResult
}

/** Deterministic state transitions. Storage owns durable command receipts and transaction boundaries. */
class StudyEngine(
    private val unitLookup: UnitLookup,
    private val time: TimeSource = TimeSource { Instant.now() },
    private val ids: IdSource = IdSource { UUID.randomUUID().toString() },
    private val zone: ZoneSource = ZoneSource { ZoneOffset.UTC },
) {
    fun startSession(
        sessionId: String,
        revision: UnitRevisionRef,
    ): Result<StudySession> {
        val unit = unitLookup.find(revision) ?: return Result.failure(IllegalArgumentException("Unit revision is unavailable."))
        if (unit.id != revision.unitId || unit.version != revision.unitVersion) {
            return Result.failure(IllegalArgumentException("Resolved unit does not match the pinned revision."))
        }
        val startedAt = time.now()
        val event = LearningEvent(ids.next(), "start:$sessionId", sessionId, revision, EventType.SESSION_STARTED, startedAt, unit.entry)
        return Result.success(StudySession(sessionId, revision, unit, SessionStatus.ACTIVE, unit.entry, events = listOf(event)))
    }

    fun handle(
        session: StudySession,
        command: StudyCommand,
    ): TransitionResult {
        if (command.commandId.isBlank() || command.commandId in session.consumedCommandIds) {
            return TransitionResult.Rejected(session, TransitionError.DuplicateCommand)
        }
        if (command is StudyCommand.Resume) {
            if (session.status != SessionStatus.PAUSED) return rejected(session, TransitionError.SessionNotPaused)
            return accept(session, command, null, EventType.SESSION_RESUMED, status = SessionStatus.ACTIVE)
        }
        if (command is StudyCommand.Pause) {
            if (session.status != SessionStatus.ACTIVE) return rejected(session, TransitionError.SessionNotActive)
            return accept(session, command, eventType = EventType.SESSION_PAUSED, status = SessionStatus.PAUSED)
        }
        if (session.status != SessionStatus.ACTIVE) return rejected(session, TransitionError.SessionNotActive)

        val activity = session.unit.activities[session.currentActivityId] ?: return rejected(session, TransitionError.ActivityNotReady)
        val previous = session.activityProgress()
        return when (command) {
            is StudyCommand.SelectChoice -> selectChoice(session, command, activity, previous)
            is StudyCommand.SubmitChoice -> submitChoice(session, command, activity, previous)
            is StudyCommand.RevealHint -> revealHint(session, command, activity, previous)
            is StudyCommand.RevealSolution -> revealSolution(session, command, activity, previous)
            is StudyCommand.SaveProofDraft -> saveDraft(session, command, activity, previous)
            is StudyCommand.SubmitProof -> submitProof(session, command, activity, previous)
            is StudyCommand.RecordRubric -> recordRubric(session, command, activity, previous)
            is StudyCommand.RecordReflection -> recordReflection(session, command, activity, previous)
            is StudyCommand.SkipActivity -> skip(session, command, activity, previous)
            is StudyCommand.Continue -> continueActivity(session, command, activity, previous)
            is StudyCommand.Pause, is StudyCommand.Resume -> error("Handled above")
        }
    }

    private fun selectChoice(
        session: StudySession,
        command: StudyCommand.SelectChoice,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        val choice = activity as? Choice ?: return rejected(session, TransitionError.WrongActivityType)
        if (choice.options.none { it == command.optionId }) return rejected(session, TransitionError.InvalidSelection)
        if (progress.feedbackVisible && progress.firstChoiceBranch == true) return rejected(session, TransitionError.RetryNotAvailable)
        return accept(session, command, activityProgress = progress.copy(selectedOption = command.optionId, feedbackVisible = false))
    }

    private fun submitChoice(
        session: StudySession,
        command: StudyCommand.SubmitChoice,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        val choice = activity as? Choice ?: return rejected(session, TransitionError.WrongActivityType)
        val selected = progress.selectedOption ?: return rejected(session, TransitionError.NoSelection)
        if (progress.feedbackVisible && progress.firstChoiceBranch == true) return rejected(session, TransitionError.RetryNotAvailable)
        val correct = selected == choice.expected
        val submittedAt = time.now()
        val offset = zone.offsetAt(submittedAt)
        val attempt =
            Attempt(
                ids.next(),
                command.commandId,
                session.currentActivityId,
                progress.attempts.size + 1,
                selected,
                if (correct) AttemptOutcome.CORRECT else AttemptOutcome.INCORRECT,
                AssessmentMethod.EXACT_CHOICE,
                progress.assistance,
                submittedAt,
                LocalDate.ofInstant(submittedAt, offset),
                offset,
            )
        val updated =
            progress.copy(
                attempts = progress.attempts + attempt,
                firstChoiceBranch = progress.firstChoiceBranch ?: correct,
                feedbackVisible = true,
                selectedOption = selected,
                readyToContinue = correct,
            )
        return accept(
            session,
            command,
            updated,
            EventType.CHOICE_SUBMITTED,
            mapOf(
                "outcome" to attempt.outcome.name,
                "option_id" to selected,
            ),
            attempt,
        )
    }

    private fun revealHint(
        session: StudySession,
        command: StudyCommand.RevealHint,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        val proof = activity as? Proof ?: return rejected(session, TransitionError.WrongActivityType)
        if (command.hintId !in proof.hintIds) return rejected(session, TransitionError.InvalidHint)
        val next = progress.copy(assistance = progress.assistance.copy(revealedHints = progress.assistance.revealedHints + command.hintId))
        return accept(session, command, next, EventType.HINT_REVEALED, mapOf("hint_id" to command.hintId))
    }

    private fun revealSolution(
        session: StudySession,
        command: StudyCommand.RevealSolution,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        if (activity !is Proof) return rejected(session, TransitionError.WrongActivityType)
        val next = progress.copy(assistance = progress.assistance.copy(solutionRevealed = true))
        return accept(session, command, next, EventType.SOLUTION_REVEALED)
    }

    private fun saveDraft(
        session: StudySession,
        command: StudyCommand.SaveProofDraft,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        if (activity !is Proof) return rejected(session, TransitionError.WrongActivityType)
        if (command.revision <= progress.draftRevision) return rejected(session, TransitionError.StaleDraftRevision)
        return accept(session, command, progress.copy(draft = command.text, draftRevision = command.revision))
    }

    private fun submitProof(
        session: StudySession,
        command: StudyCommand.SubmitProof,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        if (activity !is Proof) return rejected(session, TransitionError.WrongActivityType)
        val submittedAt = time.now()
        val offset = zone.offsetAt(submittedAt)
        val attempt =
            Attempt(
                ids.next(),
                command.commandId,
                session.currentActivityId,
                progress.attempts.size + 1,
                progress.draft,
                AttemptOutcome.UNASSESSED,
                AssessmentMethod.SELF_REVIEW,
                progress.assistance,
                submittedAt,
                LocalDate.ofInstant(submittedAt, offset),
                offset,
            )
        val next = progress.copy(attempts = progress.attempts + attempt, proofSubmitted = true, readyToContinue = false)
        return accept(session, command, next, EventType.PROOF_SUBMITTED, mapOf("outcome" to AttemptOutcome.UNASSESSED.name), attempt)
    }

    private fun recordRubric(
        session: StudySession,
        command: StudyCommand.RecordRubric,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        val proof = activity as? Proof ?: return rejected(session, TransitionError.WrongActivityType)
        if (!progress.proofSubmitted) return rejected(session, TransitionError.ProofNotSubmitted)
        if (command.marks.keys.any { it !in proof.rubricIds }) return rejected(session, TransitionError.InvalidRubric)
        val next = progress.copy(rubric = progress.rubric + command.marks, readyToContinue = true)
        return accept(session, command, next, EventType.RUBRIC_RECORDED, command.marks.mapValues { it.value.name })
    }

    private fun recordReflection(
        session: StudySession,
        command: StudyCommand.RecordReflection,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        if (activity !is Reflection) return rejected(session, TransitionError.WrongActivityType)
        return accept(session, command, progress.copy(reflection = command.text, readyToContinue = true), EventType.REFLECTION_RECORDED)
    }

    private fun skip(
        session: StudySession,
        command: StudyCommand.SkipActivity,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        if (activity is End) return rejected(session, TransitionError.WrongActivityType)
        if (activity is Choice && progress.firstChoiceBranch == null) return rejected(session, TransitionError.AmbiguousChoiceSkip)
        val updated = progress.copy(skipped = true, readyToContinue = true)
        return accept(session, command, updated, EventType.ACTIVITY_SKIPPED)
    }

    private fun continueActivity(
        session: StudySession,
        command: StudyCommand.Continue,
        activity: Activity,
        progress: ActivityProgress,
    ): TransitionResult {
        val target =
            when (activity) {
                is Exposition -> {
                    activity.next
                }

                is Choice -> {
                    if (progress.firstChoiceBranch == null) return rejected(session, TransitionError.ActivityNotReady)
                    if (progress.firstChoiceBranch) activity.correct else activity.incorrect
                }

                is Proof -> {
                    if (!progress.readyToContinue) return rejected(session, TransitionError.ActivityNotReady)
                    activity.next
                }

                is Reflection -> {
                    activity.next
                }

                is End -> {
                    null
                }
            }
        if (target ==
            null
        ) {
            return accept(
                session,
                command,
                progress.copy(readyToContinue = true),
                EventType.SESSION_COMPLETED,
                status = SessionStatus.COMPLETED,
            )
        }
        if (target !in session.unit.activities) return rejected(session, TransitionError.ActivityNotReady)
        val nextSession =
            session.copy(
                currentActivityId = target,
                progress =
                    session.progress + (session.currentActivityId to progress.copy(readyToContinue = true)),
            )
        return accept(nextSession, command, activityProgress = ActivityProgress())
    }

    private fun accept(
        session: StudySession,
        command: StudyCommand,
        activityProgress: ActivityProgress? = null,
        eventType: EventType? = null,
        payload: Map<String, String> = emptyMap(),
        attempt: Attempt? = null,
        status: SessionStatus = session.status,
    ): TransitionResult {
        val event =
            eventType?.let {
                LearningEvent(
                    ids.next(),
                    command.commandId,
                    session.id,
                    session.revision,
                    it,
                    time.now(),
                    session.currentActivityId,
                    payload = payload,
                )
            }
        val progress =
            if (activityProgress ==
                null
            ) {
                session.progress
            } else {
                session.progress + (session.currentActivityId to activityProgress)
            }
        val updated =
            session.copy(
                status = status,
                progress = progress,
                events = session.events + listOfNotNull(event),
                consumedCommandIds = session.consumedCommandIds + command.commandId,
            )
        return TransitionResult.Accepted(updated, listOfNotNull(event), listOfNotNull(attempt))
    }

    private fun rejected(
        session: StudySession,
        error: TransitionError,
    ) = TransitionResult.Rejected(session, error)
}
