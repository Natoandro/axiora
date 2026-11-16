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
            return LearningUnit(StableId("axiora.test.unit"), Revision(0, 1, 0), emptySet(), emptyList(), emptySet(), "intro", activities)
        }
    }
}
