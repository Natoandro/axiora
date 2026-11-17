package mg.abela.axiora.storage.android

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteConstraintException
import android.os.Bundle
import android.os.Process
import android.util.Log
import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import mg.abela.axiora.learning.UnitRevisionRef
import mg.abela.axiora.storage.CommandCommit
import mg.abela.axiora.storage.DraftWrite
import mg.abela.axiora.storage.InstalledPackage
import mg.abela.axiora.storage.SqliteLearnerStore
import mg.abela.axiora.storage.StoredNote
import mg.abela.axiora.storage.StoredSession
import java.io.File

class StorageProbe : Instrumentation() {
    private var mode: String? = null

    override fun onCreate(arguments: Bundle) {
        mode = arguments.getString("mode")
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val file = targetContext.getDatabasePath("probe.db")
        val backup = File(targetContext.filesDir, "expected.json")
        try {
            when (mode) {
                "commit" -> {
                    targetContext.deleteDatabase("probe.db")
                    AndroidLearnerSqliteDriver(file).use { driver ->
                        val store = SqliteLearnerStore(driver)
                        seed(store)
                        val receipt =
                            store.executeCommand("session", "saved") {
                                check(store.loadSession("session") != null)
                                commit("saved", 1)
                            }
                        check(!receipt.wasReplay)
                        backup.writeBytes(store.exportSnapshot())
                    }
                }

                "interrupt" -> {
                    AndroidLearnerSqliteDriver(file).use { driver ->
                        val store =
                            SqliteLearnerStore(driver) { boundary ->
                                if (boundary == "draft") {
                                    Log.i("AxioraStorageProbe", "Terminating inside uncommitted draft transaction")
                                    Process.killProcess(Process.myPid())
                                    error("Process termination returned")
                                }
                            }
                        store.executeCommand("session", "interrupted") { commit("interrupted", 2) }
                        error("Process termination was not reached")
                    }
                }

                "verify" -> {
                    verify(file, backup.readBytes())
                }

                else -> {
                    error("Choose commit, interrupt, or verify")
                }
            }
            result.putString("result", "PASS $mode")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("result", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun seed(store: SqliteLearnerStore) {
        val version = Revision(1, 0, 0)
        val revision = UnitRevisionRef(StableId("axiora.example"), version, StableId("axiora.unit"), version)
        store.registerPackage(InstalledPackage("axiora.example", "1.0.0", "a".repeat(64), "/missing", "today"))
        store.createSession(StoredSession("session", revision, "ACTIVE", "proof", byteArrayOf(), "today"))
        store.saveNote(StoredNote("note", "axiora.unit", null, "proof", "personal note", "today", "today"))
    }

    private fun verify(
        file: File,
        backup: ByteArray,
    ) {
        AndroidLearnerSqliteDriver(file).use { driver ->
            val store = SqliteLearnerStore(driver)
            check(backup.contentEquals(store.exportSnapshot()))
            val receipt = store.executeCommand("session", "saved") { error("Committed command was replayed") }
            check(receipt.wasReplay && receipt.resultPayload.contentEquals("saved".toByteArray()))
            check(store.loadSession("session")?.checkpoint?.contentEquals("saved".toByteArray()) == true)
            check(store.notes().single().revision == null)
            driver.read { connection ->
                connection.query("PRAGMA foreign_keys").use { rows -> check(rows.next() && rows.getInt(1) == 1) }
            }
            val orphan =
                runCatching {
                    driver.transaction { connection ->
                        connection.update("INSERT INTO command_result VALUES ('orphan', 'missing', 1, X'01', 'today')")
                    }
                }.exceptionOrNull()
            check(orphan is SQLiteConstraintException)
            val retry = store.executeCommand("session", "interrupted") { commit("interrupted", 2) }
            check(!retry.wasReplay)
        }

        targetContext.deleteDatabase("restored.db")
        AndroidLearnerSqliteDriver(targetContext.getDatabasePath("restored.db")).use { driver ->
            val restored = SqliteLearnerStore(driver)
            check(restored.restoreSnapshot(backup).single().packageId == "axiora.example")
            check(backup.contentEquals(restored.exportSnapshot()))
        }
        verifyMigration()
    }

    private fun verifyMigration() {
        targetContext.deleteDatabase("legacy.db")
        val file = targetContext.getDatabasePath("legacy.db")
        AndroidLearnerSqliteDriver(file).use { driver ->
            val fixture =
                targetContext.assets
                    .open("schema-v1.sql")
                    .bufferedReader()
                    .use { it.readText() }
            driver.transaction { connection ->
                fixture
                    .lineSequence()
                    .filterNot { it.startsWith("--") }
                    .joinToString("\n")
                    .split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(connection::execute)
            }
            val store = SqliteLearnerStore(driver)
            check(store.activePackages().single().packageId == "axiora.saved")
            check(store.attemptHistory("saved-session").single().contextSnapshot == "Context unavailable in schema 1")
            check(store.notes().single().text == "saved note")
            val receipt = store.executeCommand("saved-session", "saved-command") { error("Migrated receipt missing") }
            check(receipt.wasReplay && receipt.resultPayload.contentEquals(byteArrayOf(1, 2, 3)))
        }
    }

    private fun commit(
        command: String,
        revision: Long,
    ) = CommandCommit(
        "session",
        command,
        1,
        command.toByteArray(),
        "ACTIVE",
        "proof",
        command.toByteArray(),
        "today",
        "axiora.example@1.0.0/axiora.unit@1.0.0",
        draft = DraftWrite("proof", command, revision, "today"),
    )
}
