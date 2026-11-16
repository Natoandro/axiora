package mg.abela.axiora.learning

import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.Proof
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

const val EVIDENCE_POLICY_VERSION = 1
const val REVIEW_POLICY_VERSION = 1

enum class ObjectiveState { UNSEEN, PRACTICING, DEMONSTRATED, REVISIT }

data class ObjectiveObservation(
    val attemptId: String,
    val objectiveId: String,
    val skill: String,
    val assessmentFamily: String?,
    val outcome: AttemptOutcome,
    val method: AssessmentMethod,
    val assistance: AssistanceSnapshot,
    val firstSubmission: Boolean,
    val submittedAt: java.time.Instant,
    val localStudyDate: LocalDate,
    val zoneOffset: ZoneOffset,
    val policyVersion: Int = EVIDENCE_POLICY_VERSION,
) {
    val eligible: Boolean
        get() =
            firstSubmission && !assistance.assisted && method == AssessmentMethod.EXACT_CHOICE &&
                assessmentFamily != null && skill in setOf("recall", "recognition") &&
                outcome in setOf(AttemptOutcome.CORRECT, AttemptOutcome.INCORRECT)
}

data class ObjectiveSummary(
    val objectiveId: String,
    val state: ObjectiveState,
    val observationIds: List<String>,
    val policyVersion: Int = EVIDENCE_POLICY_VERSION,
)

data class ReviewTask(
    val objectiveId: String,
    val unitRevision: UnitRevisionRef,
    val policyVersion: Int = REVIEW_POLICY_VERSION,
    val stage: Int = 0,
    val dueDate: LocalDate,
    val lastAssessmentFamily: String? = null,
)

data class ReviewTaskKey(
    val objectiveId: String,
    val unitRevision: UnitRevisionRef,
    val policyVersion: Int,
)

object EvidencePolicy {
    fun observations(session: StudySession): List<ObjectiveObservation> {
        val objectiveById = session.unit.objectives.associateBy { it.id }
        return session.progress.flatMap { (activityId, progress) ->
            when (val activity = session.unit.activities[activityId]) {
                is Choice -> {
                    val (objectiveId, family) = activity.evidence ?: return@flatMap emptyList()
                    val objective = objectiveById[objectiveId] ?: return@flatMap emptyList()
                    progress.attempts.map { attempt ->
                        ObjectiveObservation(
                            attempt.id,
                            objectiveId,
                            objective.skill,
                            family,
                            attempt.outcome,
                            attempt.method,
                            attempt.assistance,
                            attempt.sequence == 1,
                            attempt.submittedAt,
                            attempt.localStudyDate,
                            attempt.zoneOffset,
                        )
                    }
                }

                is Proof -> {
                    val objective = objectiveById[activity.objectiveId] ?: return@flatMap emptyList()
                    progress.attempts.map { attempt ->
                        ObjectiveObservation(
                            attempt.id,
                            activity.objectiveId,
                            objective.skill,
                            null,
                            attempt.outcome,
                            attempt.method,
                            attempt.assistance,
                            attempt.sequence == 1,
                            attempt.submittedAt,
                            attempt.localStudyDate,
                            attempt.zoneOffset,
                        )
                    }
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    fun summarize(
        objectiveId: String,
        observations: List<ObjectiveObservation>,
        reviewDue: Boolean = false,
        timestampsCoherent: Boolean = true,
    ): ObjectiveSummary {
        val related = observations.filter { it.objectiveId == objectiveId }
        val eligible = related.filter(ObjectiveObservation::eligible).sortedBy { it.submittedAt }
        var state = if (related.isEmpty()) ObjectiveState.UNSEEN else ObjectiveState.PRACTICING
        val successfulEvidence = mutableListOf<ObjectiveObservation>()
        eligible.forEach { observation ->
            when (observation.outcome) {
                AttemptOutcome.CORRECT -> {
                    successfulEvidence += observation
                    if (timestampsCoherent && demonstrates(successfulEvidence)) state = ObjectiveState.DEMONSTRATED
                }

                AttemptOutcome.INCORRECT -> {
                    if (state == ObjectiveState.DEMONSTRATED) state = ObjectiveState.REVISIT
                    successfulEvidence.clear()
                }

                AttemptOutcome.UNASSESSED -> {
                    Unit
                }
            }
        }
        if (reviewDue) state = ObjectiveState.REVISIT
        return ObjectiveSummary(objectiveId, state, related.map { it.attemptId })
    }

    private fun demonstrates(observations: List<ObjectiveObservation>): Boolean {
        val successes = observations.filter { it.outcome == AttemptOutcome.CORRECT }
        return successes.any { first ->
            successes.any { second ->
                first.assessmentFamily != second.assessmentFamily && first.localStudyDate != second.localStudyDate &&
                    Duration.between(first.submittedAt, second.submittedAt).abs() >= Duration.ofHours(24)
            }
        }
    }
}

object ReviewScheduler {
    private val intervals = listOf(1L, 3L, 7L, 14L, 30L)

    fun deduplicate(tasks: List<ReviewTask>): List<ReviewTask> =
        tasks
            .groupBy { ReviewTaskKey(it.objectiveId, it.unitRevision, it.policyVersion) }
            .values
            .map { duplicates -> duplicates.minWith(compareBy<ReviewTask> { it.dueDate }.thenBy { it.stage }) }
            .sortedWith(compareBy<ReviewTask> { it.dueDate }.thenBy { it.objectiveId })

    /** Applies only a due, eligible observation. Incoherent timestamps never promote or reset evidence. */
    fun apply(
        task: ReviewTask?,
        observation: ObjectiveObservation,
        revision: UnitRevisionRef? = null,
        coherentTime: Boolean = true,
    ): ReviewTask? {
        if (!observation.eligible || !coherentTime || (task != null && observation.objectiveId != task.objectiveId)) return task
        if (task == null) {
            if (observation.outcome != AttemptOutcome.CORRECT || revision == null) return null
            return ReviewTask(
                observation.objectiveId,
                revision,
                dueDate = observation.localStudyDate.plusDays(1),
                lastAssessmentFamily = observation.assessmentFamily,
            )
        }
        if (observation.localStudyDate.isBefore(task.dueDate)) return task
        if (observation.assessmentFamily == task.lastAssessmentFamily) return task
        val nextStage = if (observation.outcome == AttemptOutcome.CORRECT) (task.stage + 1).coerceAtMost(intervals.lastIndex) else 0
        val interval = intervals[nextStage]
        return task.copy(
            stage = nextStage,
            dueDate = observation.localStudyDate.plusDays(interval),
            lastAssessmentFamily = observation.assessmentFamily,
        )
    }
}

object ClockPolicy {
    /** Event-list order is the session's logical order; a backward timestamp suspends evidence promotion. */
    fun timestampsCoherent(events: List<LearningEvent>): Boolean =
        events.zipWithNext().none { (earlier, later) -> later.occurrence.isBefore(earlier.occurrence) }
}

enum class RecommendationKind { RESUME, REVIEW, STUDY_NEXT }

data class Recommendation(
    val kind: RecommendationKind,
    val targetId: String,
    val reason: String,
)

object RecommendationPolicy {
    fun create(
        resumableSessionId: String?,
        dueObjectives: List<String>,
        nextUnitId: String?,
    ): List<Recommendation> =
        buildList {
            if (resumableSessionId != null) add(Recommendation(RecommendationKind.RESUME, resumableSessionId, "You have unfinished work."))
            dueObjectives.distinct().take(3 - size).forEach {
                add(Recommendation(RecommendationKind.REVIEW, it, "This idea is due for review."))
            }
            if (nextUnitId != null &&
                size < 3
            ) {
                add(Recommendation(RecommendationKind.STUDY_NEXT, nextUnitId, "Continue with the next available unit."))
            }
        }
}
