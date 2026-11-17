package mg.abela.axiora.storage

import mg.abela.axiora.content.End
import mg.abela.axiora.content.Exposition
import mg.abela.axiora.content.LearningUnit
import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import mg.abela.axiora.learning.ActivityProgress
import mg.abela.axiora.learning.SessionStatus
import mg.abela.axiora.learning.StudySession
import mg.abela.axiora.learning.TransitionError
import mg.abela.axiora.learning.TransitionResult
import mg.abela.axiora.learning.UnitRevisionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StudyCodecsTest {
    private val revision =
        UnitRevisionRef(
            StableId("axiora.example"),
            Revision(1, 0, 0),
            StableId("axiora.unit"),
            Revision(2, 1, 0),
        )
    private val unit =
        LearningUnit(
            revision.unitId,
            revision.unitVersion,
            emptySet(),
            emptyList(),
            setOf("source"),
            "intro",
            mapOf("intro" to Exposition(listOf("source"), "finish"), "finish" to End(listOf("source"))),
        )
    private val codec = BinaryStudySessionCodec { requested -> if (requested == revision) unit else null }

    @Test
    fun `checkpoint codec round trips draft and resolves its pinned unit`() {
        val original =
            StudySession(
                "session-1",
                revision,
                unit,
                SessionStatus.PAUSED,
                "intro",
                mapOf("intro" to ActivityProgress(draft = "proof work", draftRevision = 12, readyToContinue = true)),
                consumedCommandIds = setOf("cmd-a", "cmd-b"),
            )

        assertEquals(original, codec.decode(codec.version, codec.encode(original)))
    }

    @Test
    fun `command result codec preserves rejection and replay state`() {
        val original =
            TransitionResult.Rejected(
                StudySession("session-1", revision, unit, SessionStatus.ACTIVE, "intro"),
                TransitionError.ActivityNotReady,
            )
        val results = BinaryTransitionResultCodec(codec)

        assertEquals(original, results.decode(results.version, results.encode(original)))
    }
}
