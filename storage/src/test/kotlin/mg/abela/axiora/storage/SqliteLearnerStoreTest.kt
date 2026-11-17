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
            commit("first", "first").copy(draft = DraftWrite("proof", "new", 4, "2026-11-17T00:00:00Z"))
        }
        assertThrows(IllegalStateException::class.java) {
            store.executeCommand("session-1", "stale") {
                commit("stale", "must-not-commit").copy(draft = DraftWrite("proof", "old", 3, "2026-11-16T00:00:00Z"))
            }
        }

        assertEquals("first", String(requireNotNull(store.loadSession("session-1")).checkpoint))
        val retry = store.executeCommand("session-1", "stale") { commit("stale", "retry") }
        assertEquals("retry", String(retry.resultPayload))
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
