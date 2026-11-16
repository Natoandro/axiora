package mg.abela.axiora.learning

import mg.abela.axiora.content.Activity
import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.End
import mg.abela.axiora.content.Exposition
import mg.abela.axiora.content.LearningUnit
import mg.abela.axiora.content.Objective
import mg.abela.axiora.content.Proof
import mg.abela.axiora.content.Reflection
import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class StudyEngineTest {
    private val unit = fixtureUnit()
    private val revision = UnitRevisionRef(StableId("axiora.test"), Revision(0, 1, 0), unit.id, unit.version)
    private var id = 0
    private val engine =
        StudyEngine(
            UnitLookup { requested -> unit.takeIf { requested == revision } },
            TimeSource { Instant.parse("2026-11-16T12:00:00Z") },
            IdSource { "id-${++id}" },
        )

    @Test fun `wrong choice retry keeps first branch and both attempts`() {
        var session = engine.startSession("s1", revision).getOrThrow()
        session = accepted(session, StudyCommand.Continue("continue-intro"))
        session = accepted(session, StudyCommand.SelectChoice("select-1", "wrong"))
        val submitted = engine.handle(session, StudyCommand.SubmitChoice("submit-1"))
        val firstSubmission = assertIs<TransitionResult.Accepted>(submitted)
        session = firstSubmission.session
        assertEquals(AttemptOutcome.INCORRECT, firstSubmission.newAttempts.single().outcome)

        session = accepted(session, StudyCommand.SelectChoice("select-2", "right"))
        val retry = engine.handle(session, StudyCommand.SubmitChoice("submit-2"))
        val retried = assertIs<TransitionResult.Accepted>(retry)
        session = retried.session
        assertEquals(AttemptOutcome.CORRECT, retried.newAttempts.single().outcome)
        assertEquals(2, session.activityProgress("question").attempts.size)
        assertEquals(false, session.activityProgress("question").firstChoiceBranch)

        session = accepted(session, StudyCommand.Continue("continue-question"))
        assertEquals("remediation", session.currentActivityId)
    }

    @Test fun `proof assistance is captured and self review stays unassessed`() {
        var session = engine.startSession("s2", revision).getOrThrow()
        session = accepted(session, StudyCommand.Continue("continue-intro"))
        session = accepted(session, StudyCommand.SelectChoice("select", "right"))
        session = accepted(session, StudyCommand.SubmitChoice("submit"))
        session = accepted(session, StudyCommand.Continue("continue-question"))
        session = accepted(session, StudyCommand.Continue("continue-good"))
        session = accepted(session, StudyCommand.RevealHint("hint", "quotient"))
        session = accepted(session, StudyCommand.SaveProofDraft("draft", "Because R/M is a field...", 1))
        val result = engine.handle(session, StudyCommand.SubmitProof("proof-submit"))
        val proofResult = assertIs<TransitionResult.Accepted>(result)
        val attempt = proofResult.newAttempts.single()
        assertEquals(AttemptOutcome.UNASSESSED, attempt.outcome)
        assertEquals(AssessmentMethod.SELF_REVIEW, attempt.method)
        assertEquals(setOf("quotient"), attempt.assistance.revealedHints)
        session = proofResult.session

        session = accepted(session, StudyCommand.RecordRubric("rubric", mapOf("field" to RubricMark.MET)))
        session = accepted(session, StudyCommand.Continue("continue-proof"))
        assertEquals("reflection", session.currentActivityId)
        assertEquals(
            AttemptOutcome.UNASSESSED,
            session
                .activityProgress("proof")
                .attempts
                .single()
                .outcome,
        )
    }

    @Test fun `skipping proof completes through later activities without creating an attempt`() {
        var session = engine.startSession("s3", revision).getOrThrow()
        session = accepted(session, StudyCommand.Continue("continue-intro"))
        session = accepted(session, StudyCommand.SelectChoice("select", "right"))
        session = accepted(session, StudyCommand.SubmitChoice("submit"))
        session = accepted(session, StudyCommand.Continue("continue-question"))
        session = accepted(session, StudyCommand.Continue("continue-good"))
        session = accepted(session, StudyCommand.SkipActivity("skip-proof"))
        session = accepted(session, StudyCommand.Continue("continue-skipped-proof"))
        assertEquals("reflection", session.currentActivityId)
        session = accepted(session, StudyCommand.Continue("continue-reflection"))
        assertEquals("end", session.currentActivityId)
        session = accepted(session, StudyCommand.Continue("complete-session"))
        assertEquals(SessionStatus.COMPLETED, session.status)
        assertEquals(emptyList(), session.activityProgress("proof").attempts)
        assertEquals(1, session.events.count { it.type == EventType.SESSION_COMPLETED })
    }

    @Test fun `duplicate command is rejected without mutating session`() {
        val session = engine.startSession("s4", revision).getOrThrow()
        val once = assertIs<TransitionResult.Accepted>(engine.handle(session, StudyCommand.Continue("advance"))).session
        val duplicate = engine.handle(once, StudyCommand.Continue("advance"))
        val rejected = assertIs<TransitionResult.Rejected>(duplicate)
        assertEquals(TransitionError.DuplicateCommand, rejected.error)
        assertEquals(once, rejected.session)
        assertNotEquals(session.currentActivityId, once.currentActivityId)

        val paused = accepted(once, StudyCommand.Pause("pause"))
        assertEquals(SessionStatus.PAUSED, paused.status)
        assertEquals(
            TransitionError.SessionNotActive,
            assertIs<TransitionResult.Rejected>(engine.handle(paused, StudyCommand.Continue("while-paused"))).error,
        )
        assertEquals(SessionStatus.ACTIVE, accepted(paused, StudyCommand.Resume("resume")).status)
    }

    @Test fun `fixture run keeps retries hinted attempts and proof self review out of demonstrated evidence`() {
        var session = engine.startSession("fixture-run", revision).getOrThrow()
        session = accepted(session, StudyCommand.Continue("fixture-intro"))
        session = accepted(session, StudyCommand.SelectChoice("fixture-wrong-select", "wrong"))
        session = accepted(session, StudyCommand.SubmitChoice("fixture-wrong-submit"))
        session = accepted(session, StudyCommand.SelectChoice("fixture-retry-select", "right"))
        session = accepted(session, StudyCommand.SubmitChoice("fixture-retry-submit"))
        session = accepted(session, StudyCommand.Continue("fixture-question"))
        session = accepted(session, StudyCommand.Continue("fixture-remediation"))
        session = accepted(session, StudyCommand.RevealHint("fixture-hint", "quotient"))
        session = accepted(session, StudyCommand.SaveProofDraft("fixture-draft", "A proof attempt.", 1))
        session = accepted(session, StudyCommand.SubmitProof("fixture-proof"))
        session = accepted(session, StudyCommand.RecordRubric("fixture-rubric", mapOf("field" to RubricMark.MET)))
        session = accepted(session, StudyCommand.Continue("fixture-proof-next"))
        session = accepted(session, StudyCommand.RecordReflection("fixture-reflection", "Need another example."))
        session = accepted(session, StudyCommand.Continue("fixture-reflection-next"))
        session = accepted(session, StudyCommand.Continue("fixture-end"))

        assertEquals(SessionStatus.COMPLETED, session.status)
        val observations = EvidencePolicy.observations(session)
        assertEquals(3, observations.size)
        assertEquals(true, observations.first().eligible)
        assertEquals(false, observations.last().eligible)
        assertEquals(AttemptOutcome.UNASSESSED, observations.last().outcome)
        assertEquals(ObjectiveState.PRACTICING, EvidencePolicy.summarize("objective", observations).state)
        assertEquals(
            AttemptOutcome.UNASSESSED,
            session
                .activityProgress("proof")
                .attempts
                .single()
                .outcome,
        )
        assertEquals(true, ClockPolicy.timestampsCoherent(session.events))
        val backwardEvent =
            session.events.last().copy(
                occurrence =
                    session.events
                        .first()
                        .occurrence
                        .minusSeconds(1),
            )
        assertEquals(false, ClockPolicy.timestampsCoherent(session.events.dropLast(1) + backwardEvent))
    }

    @Test fun `only two delayed unassisted first successes in distinct families demonstrate recognition`() {
        val firstAt = Instant.parse("2026-11-16T12:00:00Z")
        val first = observation("a1", "family-a", firstAt, LocalDate.parse("2026-11-16"))
        val sameDay = observation("a2", "family-b", firstAt.plusSeconds(3600), LocalDate.parse("2026-11-16"))
        val nextDay = observation("a3", "family-b", firstAt.plusSeconds(86400), LocalDate.parse("2026-11-17"))
        assertEquals(ObjectiveState.PRACTICING, EvidencePolicy.summarize("objective", listOf(first, sameDay)).state)
        assertEquals(ObjectiveState.DEMONSTRATED, EvidencePolicy.summarize("objective", listOf(first, nextDay)).state)
        assertEquals(
            ObjectiveState.PRACTICING,
            EvidencePolicy.summarize("objective", listOf(first, nextDay.copy(assessmentFamily = "family-a"))).state,
        )
        assertEquals(
            ObjectiveState.PRACTICING,
            EvidencePolicy.summarize("objective", listOf(first, nextDay), timestampsCoherent = false).state,
        )
        assertEquals(
            ObjectiveState.PRACTICING,
            EvidencePolicy
                .summarize(
                    "objective",
                    listOf(
                        first,
                        observation("a4", "family-c", firstAt.plusSeconds(172800), LocalDate.parse("2026-11-18"), AttemptOutcome.INCORRECT),
                    ),
                ).state,
        )
        val afterDemonstration =
            observation(
                "a5",
                "family-c",
                firstAt.plusSeconds(172800),
                LocalDate.parse("2026-11-18"),
                AttemptOutcome.INCORRECT,
            )
        assertEquals(
            ObjectiveState.REVISIT,
            EvidencePolicy.summarize("objective", listOf(first, nextDay, afterDemonstration)).state,
        )
    }

    @Test fun `review tasks deduplicate families and follow the interval ladder`() {
        val at = Instant.parse("2026-11-16T12:00:00Z")
        val first = observation("r1", "family-a", at, LocalDate.parse("2026-11-16"))
        var task = ReviewScheduler.apply(null, first, revision)!!
        assertEquals(LocalDate.parse("2026-11-17"), task.dueDate)
        val beforeDue = observation("r2", "family-b", at.plusSeconds(86400), LocalDate.parse("2026-11-16"))
        assertEquals(task, ReviewScheduler.apply(task, beforeDue))
        val dueSameFamily = observation("r3", "family-a", at.plusSeconds(86400), LocalDate.parse("2026-11-17"))
        assertEquals(task, ReviewScheduler.apply(task, dueSameFamily))
        val dueFresh = observation("r4", "family-b", at.plusSeconds(86400), LocalDate.parse("2026-11-17"))
        task = ReviewScheduler.apply(task, dueFresh)!!
        assertEquals(1, task.stage)
        assertEquals(LocalDate.parse("2026-11-20"), task.dueDate)
        val duplicate = task.copy(dueDate = task.dueDate.plusDays(2))
        assertEquals(listOf(task), ReviewScheduler.deduplicate(listOf(task, duplicate)))
        val dueError = observation("r5", "family-c", at.plusSeconds(86400 * 4), LocalDate.parse("2026-11-20"), AttemptOutcome.INCORRECT)
        val reset = ReviewScheduler.apply(task, dueError)!!
        assertEquals(0, reset.stage)
        assertEquals(LocalDate.parse("2026-11-21"), reset.dueDate)
        assertEquals(3, RecommendationPolicy.create("session", listOf("a", "a", "b", "c"), "unit").size)
    }

    @Test fun `same commands clock and ids replay to identical state and event facts`() {
        fun replay(): StudySession {
            var replayId = 0
            val deterministic =
                StudyEngine(
                    UnitLookup { requested -> unit.takeIf { requested == revision } },
                    TimeSource { Instant.parse("2026-11-16T12:00:00Z") },
                    IdSource { "replay-${++replayId}" },
                )
            var session = deterministic.startSession("replay-session", revision).getOrThrow()
            session = assertIs<TransitionResult.Accepted>(deterministic.handle(session, StudyCommand.Continue("replay-next"))).session
            session =
                assertIs<TransitionResult.Accepted>(
                    deterministic.handle(session, StudyCommand.SelectChoice("replay-select", "right")),
                ).session
            return assertIs<TransitionResult.Accepted>(deterministic.handle(session, StudyCommand.SubmitChoice("replay-submit"))).session
        }
        assertEquals(replay(), replay())
    }

    @Test fun `attempt snapshots retain the local date and offset used at submission`() {
        val shifted =
            StudyEngine(
                UnitLookup { requested -> unit.takeIf { requested == revision } },
                TimeSource { Instant.parse("2026-11-16T23:30:00Z") },
                IdSource { "zone-id" },
                ZoneSource { ZoneOffset.ofHours(2) },
            )
        var session = shifted.startSession("zone-session", revision).getOrThrow()
        session = assertIs<TransitionResult.Accepted>(shifted.handle(session, StudyCommand.Continue("zone-next"))).session
        session = assertIs<TransitionResult.Accepted>(shifted.handle(session, StudyCommand.SelectChoice("zone-select", "right"))).session
        session = assertIs<TransitionResult.Accepted>(shifted.handle(session, StudyCommand.SubmitChoice("zone-submit"))).session
        val attempt = session.activityProgress("question").attempts.single()
        assertEquals(LocalDate.parse("2026-11-17"), attempt.localStudyDate)
        assertEquals(ZoneOffset.ofHours(2), attempt.zoneOffset)
    }

    private fun observation(
        id: String,
        family: String,
        at: Instant,
        date: LocalDate,
        outcome: AttemptOutcome = AttemptOutcome.CORRECT,
    ) = ObjectiveObservation(
        id,
        "objective",
        "recognition",
        family,
        outcome,
        AssessmentMethod.EXACT_CHOICE,
        AssistanceSnapshot(),
        true,
        at,
        date,
        ZoneOffset.UTC,
    )

    private fun accepted(
        session: StudySession,
        command: StudyCommand,
    ): StudySession = assertIs<TransitionResult.Accepted>(engine.handle(session, command)).session

    companion object {
        private fun fixtureUnit(): LearningUnit {
            val activities: Map<String, Activity> =
                mapOf(
                    "intro" to Exposition(emptyList(), "question"),
                    "question" to Choice(emptyList(), setOf("right", "wrong"), "right", "good", "remediation", "objective" to "family"),
                    "good" to Exposition(emptyList(), "proof"),
                    "remediation" to Exposition(emptyList(), "proof"),
                    "proof" to Proof(emptyList(), "objective", "reflection", setOf("quotient"), setOf("field", "domain")),
                    "reflection" to Reflection(emptyList(), "end"),
                    "end" to End(emptyList()),
                )
            return LearningUnit(
                StableId("axiora.test.unit"),
                Revision(0, 1, 0),
                emptySet(),
                listOf(Objective("objective", StableId("axiora.test.concept"), "recognition")),
                emptySet(),
                "intro",
                activities,
            )
        }
    }
}
