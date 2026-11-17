package mg.abela.axiora.storage

import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import mg.abela.axiora.learning.UnitRevisionRef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteLearnerStoreTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `duplicate command returns original receipt without rerunning transition`() {
        val store = seededStore()
        var calls = 0
        val first = store.executeCommand("session-1", "command-1") { commit("command-1", "changed").also { calls++ } }
        val replay = store.executeCommand("session-1", "command-1") { error("replay must not invoke transition") }

        assertEquals(1, calls)
        assertFalse(first.wasReplay)
        assertTrue(replay.wasReplay)
        assertArrayEquals(first.resultPayload, replay.resultPayload)
        assertEquals("changed", String(requireNotNull(store.loadSession("session-1")).checkpoint))
    }

    @Test
    fun `failed draft write rolls back checkpoint and receipt`() {
        val store = seededStore()
        store.executeCommand("session-1", "first") {
            commit("first", "first").copy(draft = DraftWrite("proof", "original", 1, "2026-11-16T00:00:00Z"))
        }
        store.executeCommand("session-1", "edit") {
            commit("edit", "updated").copy(draft = DraftWrite("proof", "new", 4, "2026-11-17T00:00:00Z"))
        }
        val updated = store.exportSnapshot()
        assertThrows(IllegalStateException::class.java) {
            store.executeCommand("session-1", "stale") {
                commit("stale", "must-not-commit").copy(draft = DraftWrite("proof", "old", 3, "2026-11-16T00:00:00Z"))
            }
        }

        assertEquals("new", SnapshotJson.decode(updated).getValue("draft").single()["text"])
        assertArrayEquals(updated, store.exportSnapshot())
        val retry = store.executeCommand("session-1", "stale") { commit("stale", "retry") }
        assertEquals("retry", String(retry.resultPayload))
    }

    @Test
    fun `editing a note preserves its creation time`() {
        val store = seededStore()
        val original = StoredNote("note", "axiora.unit", null, "proof", "original", "yesterday", "yesterday")
        val edited = original.copy(revision = "1.0.0", text = "edited", createdAt = "today", modifiedAt = "today")

        assertEquals(1, store.saveNote(original))
        assertEquals(1, store.saveNote(edited))
        assertEquals(listOf(edited.copy(createdAt = original.createdAt)), store.notes())
    }

    @Test
    fun `backup restores learner data and refuses a nonempty profile`() {
        val source = seededStore()
        source.executeCommand("session-1", "command-1") {
            commit("command-1", "checkpoint").copy(
                draft = DraftWrite("proof", "a personal proof", 1, "2026-11-17T00:00:00Z"),
            )
        }
        val backup = source.exportSnapshot()
        val restored = SqliteLearnerStore(temp.resolve("restored.db"))
        restored.restoreSnapshot(backup)
        assertEquals("checkpoint", String(requireNotNull(restored.loadSession("session-1")).checkpoint))
        assertTrue(restored.isEmpty().not())
        assertThrows(IllegalArgumentException::class.java) { restored.restoreSnapshot(backup) }

        val corruptTarget = SqliteLearnerStore(temp.resolve("corrupt.db"))
        val corrupt = backup.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { corruptTarget.restoreSnapshot(corrupt) }
        assertTrue(corruptTarget.isEmpty())
    }

    @Test
    fun `every command write boundary rolls back all records and permits retry after reopen`() {
        val store = seededStore()
        store.saveNote(StoredNote("note", "axiora.unit", null, "proof", "keep", "today", "today"))
        java.sql.DriverManager.getConnection("jdbc:sqlite:${temp.resolve("learner.db")}").use { connection ->
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO objective_summary VALUES ('objective', 'axiora.example@1.0.0/axiora.unit@1.0.0', 'practicing', X'0102', 1)",
                )
            }
        }
        val before = store.exportSnapshot()
        listOf(
            "before-checkpoint",
            "checkpoint",
            "receipt",
            "draft",
            "attempts",
            "observations",
            "events",
            "projections",
            "before-commit",
        ).forEach { point ->
            val failing = SqliteLearnerStore(temp.resolve("learner.db")) { if (it == point) error("injected $point") }
            assertThrows(IllegalStateException::class.java) {
                failing.executeCommand("session-1", "fault") { fullCommit("fault") }
            }
            assertArrayEquals(before, SqliteLearnerStore(temp.resolve("learner.db")).exportSnapshot(), point)
        }
        store.executeCommand("session-1", "fault") { fullCommit("fault") }
        val replay = SqliteLearnerStore(temp.resolve("learner.db")).executeCommand("session-1", "fault") { error("replayed") }
        assertTrue(replay.wasReplay)
    }

    @Test
    fun `all record types round trip with missing content and derived rebuild preserves primary data`() {
        val source = seededStore()
        source.executeCommand("session-1", "all") { fullCommit("all") }
        source.saveNote(StoredNote("note", "axiora.unit", "1.0.0", "proof", "personal note", "today", "today"))
        java.sql.DriverManager.getConnection("jdbc:sqlite:${temp.resolve("learner.db")}").use { connection ->
            connection.createStatement().use {
                it.execute("INSERT INTO objective_summary VALUES ('objective', 'revision', 'practicing', X'0102', 1)")
                it.execute("INSERT INTO review_task VALUES ('objective', 'revision', 1, 2, '2026-11-20', NULL, 'pending')")
            }
        }
        val backup = source.exportSnapshot()
        val target = SqliteLearnerStore(temp.resolve("all-restored.db"))
        assertEquals(1, target.restoreSnapshot(backup).size)
        assertArrayEquals(backup, target.exportSnapshot())
        target.clearDerivedSummaries()
        val tables = SnapshotJson.decode(target.exportSnapshot())
        val original = SnapshotJson.decode(backup)
        original.keys.filter { it != "objective_summary" }.forEach {
            assertArrayEquals(
                SnapshotJson.encode(mapOf(it to original.getValue(it))),
                SnapshotJson.encode(mapOf(it to tables.getValue(it))),
            )
        }
        assertTrue(tables.getValue("objective_summary").isEmpty())
        assertEquals("Readable prompt and key", tables.getValue("attempt").single()["context_snapshot"])
        val receipt = target.executeCommand("session-1", "all") { error("duplicate after restore") }
        assertTrue(receipt.wasReplay)
    }

    @Test
    fun `invalid backups roll back staged rows and never alter existing work`() {
        val source = seededStore()
        val backup = source.exportSnapshot()
        val target = SqliteLearnerStore(temp.resolve("bad.db"))
        val rows = SnapshotJson.decode(backup).toMutableMap()
        rows["draft"] =
            listOf(mapOf("session_id" to "missing", "activity_id" to "proof", "text" to "bad", "revision" to 1, "modified_at" to "today"))
        assertThrows(Exception::class.java) { target.restoreSnapshot(SnapshotJson.encode(rows)) }
        assertTrue(target.isEmpty())
        listOf(
            String(backup) + " {}",
            String(backup).replace("\"version\":2", "\"version\":\"2\""),
            "{\"version\":1,\"version\":1}",
        ).forEach {
            assertThrows(Exception::class.java) { target.restoreSnapshot(it.toByteArray()) }
            assertTrue(target.isEmpty())
        }
        assertThrows(Exception::class.java) { source.restoreSnapshot(backup) }
        assertArrayEquals(backup, source.exportSnapshot())
    }

    @Test
    fun `saved schema one migrates without changing learner work and future schema refuses writes`() {
        val path = temp.resolve("old.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            val sql = requireNotNull(javaClass.getResource("/schema-v1.sql")).readText()
            connection.createStatement().use { statement ->
                sql.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                statement.execute("INSERT INTO package_revision VALUES ('axiora.old', '1.0.0', '${"b".repeat(64)}', '/missing', 'today')")
                statement.execute("INSERT INTO note VALUES ('old', 'axiora.unit', NULL, 'proof', 'preserved work', 'today', 'today')")
            }
        }
        val migrated = SqliteLearnerStore(path)
        assertEquals("preserved work", migrated.notes().first { it.noteId == "old" }.text)
        assertTrue(migrated.packageRevisions().all { it.active })
        assertEquals("yes", migrated.attemptHistory("saved-session").single().response)
        assertEquals("Context unavailable in schema 1", migrated.attemptHistory("saved-session").single().contextSnapshot)
        val saved = SnapshotJson.decode(migrated.exportSnapshot())
        assertEquals("saved proof draft", saved.getValue("draft").single()["text"])
        assertEquals(8L, saved.getValue("learning_event").single()["sequence"])
        assertEquals(1, saved.getValue("review_task").size)
        val before = migrated.exportSnapshot()
        assertArrayEquals(before, SqliteLearnerStore(path).exportSnapshot())
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 99") }
        }
        assertThrows(IllegalArgumentException::class.java) { SqliteLearnerStore(path) }
    }

    @Test
    fun `version one backup upgrades without inventing historical prompt context`() {
        val source = seededStore()
        source.executeCommand("session-1", "legacy") { fullCommit("legacy") }
        val legacyTables =
            SnapshotJson.decode(source.exportSnapshot()).mapValues { (table, rows) ->
                rows.map { row ->
                    when (table) {
                        "package_revision" -> row - "active"
                        "attempt" -> row - "context_snapshot"
                        else -> row
                    }
                }
            }
        val legacy = String(SnapshotJson.encode(legacyTables)).replace("\"version\":2", "\"version\":1").toByteArray()
        val target = SqliteLearnerStore(temp.resolve("legacy.db"))
        target.restoreSnapshot(legacy)
        assertEquals("Context unavailable in schema 1", target.attemptHistory("session-1").single().contextSnapshot)
        assertEquals("answer", target.attemptHistory("session-1").single().response)
        assertTrue(target.packageRevisions().single().active)
        assertTrue(target.executeCommand("session-1", "legacy") { error("must replay") }.wasReplay)
    }

    private fun fullCommit(command: String): CommandCommit {
        val revision = requireNotNull(seededRevision())
        return commit(command, "durable").copy(
            draft = DraftWrite("proof", "proof draft", 7, "today"),
            attempts =
                listOf(
                    AttemptWrite(
                        "attempt-$command",
                        command,
                        "revision",
                        "choice",
                        1,
                        "answer",
                        "correct",
                        "exact_choice",
                        "{assistance}".toByteArray(),
                        "today",
                        "2026-11-17",
                        "+03:00",
                        "Readable prompt and key",
                    ),
                ),
            events =
                listOf(
                    EventWrite("event-$command", command, revision, "choice_submitted", 1, "today", "choice", "{}".toByteArray()),
                ),
            observations = listOf(ObservationWrite("attempt-$command", "objective", "recognition", "family", 1)),
            invalidatedObjectives = setOf("objective"),
        )
    }

    private fun seededRevision() =
        UnitRevisionRef(StableId("axiora.example"), Revision(1, 0, 0), StableId("axiora.unit"), Revision(1, 0, 0))

    private fun seededStore(): SqliteLearnerStore {
        val store = SqliteLearnerStore(temp.resolve("learner.db"))
        store.registerPackage(
            InstalledPackage("axiora.example", "1.0.0", "a".repeat(64), "/missing/content.axp", "2026-11-17T00:00:00Z"),
        )
        store.createSession(
            StoredSession(
                "session-1",
                UnitRevisionRef(
                    StableId("axiora.example"),
                    Revision(1, 0, 0),
                    StableId("axiora.unit"),
                    Revision(1, 0, 0),
                ),
                "ACTIVE",
                "entry",
                "initial".toByteArray(),
                "2026-11-17T00:00:00Z",
            ),
        )
        return store
    }

    private fun commit(
        commandId: String,
        checkpoint: String,
    ) = CommandCommit(
        sessionId = "session-1",
        commandId = commandId,
        resultVersion = 1,
        resultPayload = checkpoint.toByteArray(),
        sessionStatus = "ACTIVE",
        currentActivityId = "entry",
        checkpoint = checkpoint.toByteArray(),
        updatedAt = "2026-11-17T00:00:00Z",
        unitRevisionKey = "axiora.example@1.0.0/axiora.unit@1.0.0",
    )
}
