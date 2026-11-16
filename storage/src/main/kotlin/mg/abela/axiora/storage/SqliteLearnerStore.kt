package mg.abela.axiora.storage

import mg.abela.axiora.learning.UnitRevisionRef
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Versioned learner records. Payload bytes are application-versioned and never interpreted as SQL. */
class SqliteLearnerStore(
    private val database: Path,
) {
    init {
        database.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        DriverManager.getConnection(jdbcUrl()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
            }
            migrate(connection)
        }
    }

    /** Registers exact package bytes. Reimporting the same identity and hash is idempotent. */
    fun registerPackage(revision: InstalledPackage) =
        transaction { connection ->
            require(Regex("[0-9a-f]{64}").matches(revision.manifestSha256)) { "Manifest hash must be lowercase SHA-256 hex." }
            connection
                .prepareStatement(
                    """INSERT INTO package_revision(package_id, package_version, manifest_sha256, content_location, imported_at)
               VALUES (?, ?, ?, ?, ?) ON CONFLICT(package_id, package_version) DO NOTHING""",
                ).use { statement ->
                    statement.setString(1, revision.packageId)
                    statement.setString(2, revision.packageVersion)
                    statement.setString(3, revision.manifestSha256)
                    statement.setString(4, revision.contentLocation)
                    statement.setString(5, revision.importedAt)
                    statement.executeUpdate()
                }
            val savedHash =
                connection
                    .prepareStatement(
                        "SELECT manifest_sha256 FROM package_revision WHERE package_id = ? AND package_version = ?",
                    ).use { statement ->
                        statement.setString(1, revision.packageId)
                        statement.setString(2, revision.packageVersion)
                        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
            if (savedHash != revision.manifestSha256) throw PackageRevisionConflict(revision.packageId, revision.packageVersion)
        }

    fun createSession(session: StoredSession) =
        transaction { connection ->
            connection
                .prepareStatement(
                    """INSERT INTO learner_session(
                session_id, package_id, package_version, unit_id, unit_version,
                status, current_activity_id, checkpoint, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ).use { statement ->
                    statement.setString(1, session.sessionId)
                    statement.setString(2, session.revision.packageId.value)
                    statement.setString(3, session.revision.packageVersion.toString())
                    statement.setString(4, session.revision.unitId.value)
                    statement.setString(5, session.revision.unitVersion.toString())
                    statement.setString(6, session.status)
                    statement.setString(7, session.currentActivityId)
                    statement.setBytes(8, session.checkpoint)
                    statement.setString(9, session.updatedAt)
                    statement.executeUpdate()
                }
        }

    /**
     * Checks the durable receipt before running the reducer. The reducer and every resulting record
     * are committed together. A repeated command returns its original opaque result bytes.
     */
    fun executeCommand(
        sessionId: String,
        commandId: String,
        transition: () -> CommandCommit,
    ): CommandReceipt =
        transaction { connection ->
            val prior = findReceipt(connection, commandId)
            if (prior != null) {
                if (prior.sessionId != sessionId) throw CommandIdConflict(commandId)
                return@transaction prior
            }

            val commit = transition()
            require(commit.commandId == commandId) { "Transition command ID must match receipt key." }
            require(commit.sessionId == sessionId) { "Transition session ID must match receipt owner." }
            require(commit.resultVersion > 0) { "Result payload version must be positive." }
            require(commit.attempts.all { it.commandId == commandId }) { "Attempts must belong to the committed command." }
            require(commit.events.all { it.commandId == commandId }) { "Events must belong to the committed command." }
            val sessionUpdated =
                connection
                    .prepareStatement(
                        """UPDATE learner_session
               SET status = ?, current_activity_id = ?, checkpoint = ?, updated_at = ?
               WHERE session_id = ?""",
                    ).use { statement ->
                        statement.setString(1, commit.sessionStatus)
                        statement.setString(2, commit.currentActivityId)
                        statement.setBytes(3, commit.checkpoint)
                        statement.setString(4, commit.updatedAt)
                        statement.setString(5, sessionId)
                        statement.executeUpdate()
                    }
            check(sessionUpdated == 1) { "Session '$sessionId' is unavailable." }

            connection
                .prepareStatement(
                    "INSERT INTO command_result(command_id, session_id, result_version, result_payload, committed_at) VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, commandId)
                    statement.setString(2, sessionId)
                    statement.setInt(3, commit.resultVersion)
                    statement.setBytes(4, commit.resultPayload)
                    statement.setString(5, commit.updatedAt)
                    statement.executeUpdate()
                }
            commit.draft?.let { saveDraft(connection, sessionId, it) }
            commit.attempts.forEach { insertAttempt(connection, sessionId, it) }
            commit.events.forEach { insertEvent(connection, sessionId, it) }
            commit.invalidatedObjectives.forEach { objectiveId ->
                connection
                    .prepareStatement("DELETE FROM objective_summary WHERE objective_id = ? AND unit_revision_key = ?")
                    .use { statement ->
                        statement.setString(1, objectiveId)
                        statement.setString(2, commit.unitRevisionKey)
                        statement.executeUpdate()
                    }
            }
            CommandReceipt(commandId, sessionId, commit.resultVersion, commit.resultPayload, wasReplay = false)
        }

    private fun saveDraft(
        connection: Connection,
        sessionId: String,
        draft: DraftWrite,
    ) {
        val changed =
            connection
                .prepareStatement(
                    """INSERT INTO draft(session_id, activity_id, text, revision, modified_at)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(session_id, activity_id) DO UPDATE SET
                 text = excluded.text, revision = excluded.revision, modified_at = excluded.modified_at
               WHERE excluded.revision > draft.revision""",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, draft.activityId)
                    statement.setString(3, draft.text)
                    statement.setLong(4, draft.revision)
                    statement.setString(5, draft.modifiedAt)
                    statement.executeUpdate()
                }
        check(changed == 1) { "Draft revision is stale; the newer saved draft was preserved." }
    }

    private fun insertAttempt(
        connection: Connection,
        sessionId: String,
        attempt: AttemptWrite,
    ) {
        connection
            .prepareStatement(
                """INSERT INTO attempt(
               attempt_id, command_id, session_id, unit_revision_key, activity_id, sequence,
               response, outcome, method, assistance, submitted_at, local_study_date, zone_offset
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            ).use { statement ->
                statement.setString(1, attempt.id)
                statement.setString(2, attempt.commandId)
                statement.setString(3, sessionId)
                statement.setString(4, attempt.unitRevisionKey)
                statement.setString(5, attempt.activityId)
                statement.setInt(6, attempt.sequence)
                statement.setString(7, attempt.response)
                statement.setString(8, attempt.outcome)
                statement.setString(9, attempt.method)
                statement.setBytes(10, attempt.assistance)
                statement.setString(11, attempt.submittedAt)
                statement.setString(12, attempt.localStudyDate)
                statement.setString(13, attempt.zoneOffset)
                statement.executeUpdate()
            }
    }

    private fun insertEvent(
        connection: Connection,
        sessionId: String,
        event: EventWrite,
    ) {
        connection
            .prepareStatement(
                """INSERT INTO learning_event(
               event_id, command_id, session_id, package_id, package_version, unit_id,
               unit_version, event_type, payload_version, occurrence, activity_id, payload
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            ).use { statement ->
                statement.setString(1, event.id)
                statement.setString(2, event.commandId)
                statement.setString(3, sessionId)
                statement.setString(4, event.revision.packageId.value)
                statement.setString(5, event.revision.packageVersion.toString())
                statement.setString(6, event.revision.unitId.value)
                statement.setString(7, event.revision.unitVersion.toString())
                statement.setString(8, event.type)
                statement.setInt(9, event.payloadVersion)
                statement.setString(10, event.occurrence)
                statement.setString(11, event.activityId)
                statement.setBytes(12, event.payload)
                statement.executeUpdate()
            }
    }

    private fun findReceipt(
        connection: Connection,
        commandId: String,
    ): CommandReceipt? =
        connection
            .prepareStatement(
                "SELECT session_id, result_version, result_payload FROM command_result WHERE command_id = ?",
            ).use { statement ->
                statement.setString(1, commandId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        CommandReceipt(
                            commandId,
                            rows.getString(1),
                            rows.getInt(2),
                            rows.getBytes(3),
                            wasReplay = true,
                        )
                    }
                }
            }

    private fun jdbcUrl() = "jdbc:sqlite:${database.toAbsolutePath()}"

    private fun <T> transaction(block: (Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
            }
            connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val result = block(connection)
                connection.createStatement().use { it.execute("COMMIT") }
                result
            } catch (error: Throwable) {
                runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
                throw error
            }
        }

    private fun migrate(connection: Connection) {
        val version =
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        require(version <= SCHEMA_VERSION) { "Database schema $version is newer than supported schema $SCHEMA_VERSION." }
        if (version == SCHEMA_VERSION) return
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            SCHEMA_V1.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
            connection.createStatement().use { it.execute("PRAGMA user_version = $SCHEMA_VERSION") }
            connection.createStatement().use { it.execute("COMMIT") }
        } catch (error: Throwable) {
            runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        val SCHEMA_V1 =
            listOf(
                """CREATE TABLE package_revision (
                package_id TEXT NOT NULL, package_version TEXT NOT NULL, manifest_sha256 TEXT NOT NULL,
                content_location TEXT NOT NULL, imported_at TEXT NOT NULL,
                PRIMARY KEY(package_id, package_version)
            )""",
                """CREATE TABLE learner_session (
                session_id TEXT PRIMARY KEY, package_id TEXT NOT NULL, package_version TEXT NOT NULL,
                unit_id TEXT NOT NULL, unit_version TEXT NOT NULL, status TEXT NOT NULL,
                current_activity_id TEXT NOT NULL, checkpoint BLOB NOT NULL, updated_at TEXT NOT NULL,
                FOREIGN KEY(package_id, package_version) REFERENCES package_revision(package_id, package_version)
            )""",
                """CREATE TABLE command_result (
                command_id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES learner_session(session_id),
                result_version INTEGER NOT NULL CHECK(result_version > 0), result_payload BLOB NOT NULL,
                committed_at TEXT NOT NULL, UNIQUE(command_id, session_id)
            )""",
                """CREATE TABLE draft (
                session_id TEXT NOT NULL REFERENCES learner_session(session_id), activity_id TEXT NOT NULL,
                text TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision >= 0), modified_at TEXT NOT NULL,
                PRIMARY KEY(session_id, activity_id)
            )""",
                """CREATE TABLE attempt (
                attempt_id TEXT PRIMARY KEY, command_id TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL REFERENCES learner_session(session_id), unit_revision_key TEXT NOT NULL,
                activity_id TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence > 0), response TEXT NOT NULL,
                outcome TEXT NOT NULL, method TEXT NOT NULL, assistance BLOB NOT NULL, submitted_at TEXT NOT NULL,
                local_study_date TEXT NOT NULL, zone_offset TEXT NOT NULL,
                FOREIGN KEY(command_id, session_id) REFERENCES command_result(command_id, session_id)
            )""",
                """CREATE TABLE learning_event (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE,
                command_id TEXT NOT NULL,
                session_id TEXT NOT NULL REFERENCES learner_session(session_id), package_id TEXT NOT NULL,
                package_version TEXT NOT NULL, unit_id TEXT NOT NULL, unit_version TEXT NOT NULL,
                event_type TEXT NOT NULL, payload_version INTEGER NOT NULL CHECK(payload_version > 0),
                occurrence TEXT NOT NULL, activity_id TEXT, payload BLOB NOT NULL,
                FOREIGN KEY(command_id, session_id) REFERENCES command_result(command_id, session_id),
                FOREIGN KEY(package_id, package_version) REFERENCES package_revision(package_id, package_version)
            )""",
                """CREATE TABLE observation (
                attempt_id TEXT NOT NULL REFERENCES attempt(attempt_id), objective_id TEXT NOT NULL,
                skill TEXT NOT NULL, assessment_family TEXT, policy_version INTEGER NOT NULL,
                PRIMARY KEY(attempt_id, objective_id, policy_version)
            )""",
                """CREATE TABLE objective_summary (
                objective_id TEXT NOT NULL, unit_revision_key TEXT NOT NULL, state TEXT NOT NULL,
                supporting_attempts BLOB NOT NULL, policy_version INTEGER NOT NULL,
                PRIMARY KEY(objective_id, unit_revision_key, policy_version)
            )""",
                """CREATE TABLE review_task (
                objective_id TEXT NOT NULL, unit_revision_key TEXT NOT NULL, policy_version INTEGER NOT NULL,
                stage INTEGER NOT NULL CHECK(stage BETWEEN 0 AND 4), due_date TEXT NOT NULL,
                last_assessment_family TEXT, status TEXT NOT NULL,
                PRIMARY KEY(objective_id, unit_revision_key, policy_version)
            )""",
                """CREATE TABLE note (
                note_id TEXT PRIMARY KEY, content_id TEXT NOT NULL, revision TEXT, anchor TEXT NOT NULL,
                text TEXT NOT NULL, created_at TEXT NOT NULL, modified_at TEXT NOT NULL
            )""",
            )
    }
}

data class InstalledPackage(
    val packageId: String,
    val packageVersion: String,
    val manifestSha256: String,
    val contentLocation: String,
    val importedAt: String,
)

data class StoredSession(
    val sessionId: String,
    val revision: UnitRevisionRef,
    val status: String,
    val currentActivityId: String,
    val checkpoint: ByteArray,
    val updatedAt: String,
)

data class DraftWrite(
    val activityId: String,
    val text: String,
    val revision: Long,
    val modifiedAt: String,
)

data class AttemptWrite(
    val id: String,
    val commandId: String,
    val unitRevisionKey: String,
    val activityId: String,
    val sequence: Int,
    val response: String,
    val outcome: String,
    val method: String,
    val assistance: ByteArray,
    val submittedAt: String,
    val localStudyDate: String,
    val zoneOffset: String,
)

data class EventWrite(
    val id: String,
    val commandId: String,
    val revision: UnitRevisionRef,
    val type: String,
    val payloadVersion: Int,
    val occurrence: String,
    val activityId: String?,
    val payload: ByteArray,
)

data class CommandCommit(
    val sessionId: String,
    val commandId: String,
    val resultVersion: Int,
    val resultPayload: ByteArray,
    val sessionStatus: String,
    val currentActivityId: String,
    val checkpoint: ByteArray,
    val updatedAt: String,
    val unitRevisionKey: String,
    val events: List<EventWrite> = emptyList(),
    val attempts: List<AttemptWrite> = emptyList(),
    val draft: DraftWrite? = null,
    val invalidatedObjectives: Set<String> = emptySet(),
)

data class CommandReceipt(
    val commandId: String,
    val sessionId: String,
    val resultVersion: Int,
    val resultPayload: ByteArray,
    val wasReplay: Boolean,
)

class PackageRevisionConflict(
    packageId: String,
    version: String,
) : IllegalStateException("Different package bytes already exist for $packageId@$version.")

class CommandIdConflict(
    commandId: String,
) : IllegalStateException("Command ID '$commandId' was already used by another session.")
