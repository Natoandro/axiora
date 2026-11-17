package mg.abela.axiora.storage

import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.End
import mg.abela.axiora.content.LearningUnit
import mg.abela.axiora.content.Objective
import mg.abela.axiora.content.Proof
import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import mg.abela.axiora.learning.IdSource
import mg.abela.axiora.learning.StudyCommand
import mg.abela.axiora.learning.StudyEngine
import mg.abela.axiora.learning.TimeSource
import mg.abela.axiora.learning.UnitLookup
import mg.abela.axiora.learning.UnitRevisionRef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class StudyRepositoryTest {
    @TempDir
    lateinit var temp: Path

    private val old = UnitRevisionRef(StableId("axiora.example"), Revision(1, 0, 0), StableId("axiora.unit"), Revision(1, 0, 0))
    private val newer = old.copy(packageVersion = Revision(1, 1, 0), unitVersion = Revision(1, 1, 0))
    private val time = Instant.parse("2026-11-17T00:00:00Z")
    private val lookup =
        UnitLookup { ref ->
            LearningUnit(
                ref.unitId,
                ref.unitVersion,
                emptySet(),
                listOf(Objective("recognize", StableId("ideal"), "recognition")),
                setOf("source"),
                "choice",
                mapOf(
                    "choice" to Choice(listOf("source"), setOf("yes", "no"), "yes", "proof", "proof", "recognize" to "family"),
                    "proof" to Proof(listOf("source"), "prove", "end", setOf("hint"), setOf("rubric")),
                    "end" to End(listOf("source")),
                ),
            )
        }
    private var nextId = 0
    private val engine = StudyEngine(lookup, TimeSource { time }, IdSource { "id-${nextId++}" })
    private val codec = BinaryStudySessionCodec(lookup)

    private fun repository(store: SqliteLearnerStore) =
        StudyRepository(store, engine, codec, BinaryTransitionResultCodec(codec), { _, activity ->
            if (activity ==
                "choice"
            ) {
                "Is this a prime ideal? yes/no; key: yes; evaluator exact-choice v1"
            } else {
                "Prove the claim; rubric: justify each implication"
            }
        }, { time })

    private fun register(
        store: SqliteLearnerStore,
        revision: UnitRevisionRef,
    ) = store.registerPackage(
        InstalledPackage(revision.packageId.value, revision.packageVersion.toString(), "a".repeat(64), "/missing", time.toString()),
    )

    @Test
    fun `restart replays submission and resumes last committed draft while updates preserve pinned history`() {
        val db = temp.resolve("study.db")
        val store = SqliteLearnerStore(db)
        register(store, old)
        val repo = repository(store)
        repo.create(engine.startSession("old", old).getOrThrow())
        repo.handle("old", StudyCommand.SelectChoice("select", "yes"))
        val submitted = repo.handle("old", StudyCommand.SubmitChoice("submit"))
        val reopened = repository(SqliteLearnerStore(db))
        val replay = reopened.handle("old", StudyCommand.SubmitChoice("submit"))
        assertTrue(replay.wasReplay)
        assertArrayEquals(submitted.resultPayload, replay.resultPayload)
        assertEquals(1, store.attemptHistory("old").size)
        reopened.handle("old", StudyCommand.Continue("continue"))
        reopened.handle("old", StudyCommand.SaveProofDraft("save", "committed proof", 4))
        val broken = repository(SqliteLearnerStore(db) { if (it == "before-commit") error("disk failure") })
        assertThrows(IllegalStateException::class.java) { broken.handle("old", StudyCommand.SaveProofDraft("unsaved", "unsaved proof", 5)) }
        val resumed = repository(SqliteLearnerStore(db))
        assertEquals("committed proof", resumed.load("old")!!.activityProgress().draft)
        resumed.handle("old", StudyCommand.SaveProofDraft("retry", "revised proof", 6))
        // Rejected stale edits must not write a draft row or replace the checkpoint draft.
        resumed.handle("old", StudyCommand.SaveProofDraft("stale", "old text", 3))
        assertEquals("revised proof", resumed.load("old")!!.activityProgress().draft)
        register(store, newer)
        resumed.create(engine.startSession("new", newer).getOrThrow())
        assertEquals(old, resumed.load("old")!!.revision)
        assertEquals(newer, resumed.load("new")!!.revision)
        store.saveNote(StoredNote("note", "axiora.unit", "1.0.0", "proof", "my note", "today", "today"))
        store.setPackageActive(old.packageId.value, old.packageVersion.toString(), false)
        assertThrows(IllegalArgumentException::class.java) { resumed.create(engine.startSession("blocked", old).getOrThrow()) }
        assertEquals("revised proof", resumed.load("old")!!.activityProgress().draft)
        assertTrue(
            store
                .attemptHistory("old")
                .single()
                .contextSnapshot
                .contains("Is this a prime ideal?"),
        )
        assertEquals("my note", store.notes().single().text)
        resumed.handle("old", StudyCommand.RevealHint("hint-command", "hint"))
        resumed.handle("old", StudyCommand.SubmitProof("proof-submit"))
        resumed.handle("old", StudyCommand.RecordRubric("rubric-command", mapOf("rubric" to mg.abela.axiora.learning.RubricMark.MET)))
        val target = SqliteLearnerStore(temp.resolve("restored-study.db"))
        assertEquals(2, target.restoreSnapshot(store.exportSnapshot()).size)
        assertEquals("revised proof", repository(target).load("old")!!.activityProgress().draft)
        assertTrue(repository(target).handle("old", StudyCommand.SubmitChoice("submit")).wasReplay)
        assertEquals(2, target.attemptHistory("old").size)
        val proof = target.attemptHistory("old").first { it.method == "self_review" }
        assertEquals("unassessed", proof.outcome)
        assertTrue(String(proof.assistance).contains("hint"))
        val unavailableCodec = BinaryStudySessionCodec(UnitLookup { null })
        val unavailable =
            StudyRepository(
                target,
                engine,
                unavailableCodec,
                BinaryTransitionResultCodec(unavailableCodec),
                { _, _ -> error("content unavailable") },
            )
        assertThrows(IllegalArgumentException::class.java) { unavailable.load("old") }
        assertEquals("revised proof", proof.response)
        assertEquals("my note", target.notes().single().text)
    }
}
