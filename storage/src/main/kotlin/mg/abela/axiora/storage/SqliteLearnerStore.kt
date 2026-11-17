package mg.abela.axiora.storage

import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import mg.abela.axiora.learning.UnitRevisionRef
import mg.abela.axiora.validation.PackageArchiveReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Versioned learner records. Payload bytes are application-versioned and never interpreted as SQL. */
class SqliteLearnerStore(
    private val database: Path,
    private val fault: (String) -> Unit = {},
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
            require(Regex("[0-9a-f]{64}").matches(revision.manifestSha256)) {
                "Manifest hash must be lowercase SHA-256 hex."
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
            when (savedHash) {
                null -> {
                    connection
                        .prepareStatement(
                            """INSERT INTO package_revision(
                               package_id, package_version, manifest_sha256, content_location, imported_at
                               ) VALUES (?, ?, ?, ?, ?)""",
                        ).use { statement ->
                            statement.setString(1, revision.packageId)
                            statement.setString(2, revision.packageVersion)
                            statement.setString(3, revision.manifestSha256)
                            statement.setString(4, revision.contentLocation)
                            statement.setString(5, revision.importedAt)
                            statement.executeUpdate()
                        }
                }

                revision.manifestSha256 -> {
                    connection
                        .prepareStatement(
                            """UPDATE package_revision SET content_location = ?
                           WHERE package_id = ? AND package_version = ? AND manifest_sha256 = ?""",
                        ).use { statement ->
                            statement.setString(1, revision.contentLocation)
                            statement.setString(2, revision.packageId)
                            statement.setString(3, revision.packageVersion)
                            statement.setString(4, revision.manifestSha256)
                            statement.executeUpdate()
                        }
                }

                else -> {
                    throw PackageRevisionConflict(revision.packageId, revision.packageVersion)
                }
            }
        }

    /** Deactivation retains immutable bytes and all learner records. Reimport explicitly reactivates. */
    fun setPackageActive(
        packageId: String,
        version: String,
        active: Boolean,
    ) = transaction { connection ->
        connection.prepareStatement("UPDATE package_revision SET active = ? WHERE package_id = ? AND package_version = ?").use {
            it.setInt(1, if (active) 1 else 0)
            it.setString(2, packageId)
            it.setString(3, version)
            require(it.executeUpdate() == 1) { "Package revision is unavailable." }
        }
    }

    fun activePackages(): List<InstalledPackage> = packageRevisions().filter { it.active }

    /** Historical submissions remain readable without resolving content or decoding a checkpoint. */
    fun attemptHistory(sessionId: String): List<HistoricalAttempt> =
        DriverManager.getConnection(jdbcUrl()).use { connection ->
            connection
                .prepareStatement(
                    "SELECT attempt_id, response, outcome, method, assistance, context_snapshot FROM attempt WHERE session_id = ? ORDER BY sequence, attempt_id",
                ).use {
                    it.setString(1, sessionId)
                    it.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    HistoricalAttempt(
                                        rows.getString(1),
                                        rows.getString(2),
                                        rows.getString(3),
                                        rows.getString(4),
                                        rows.getBytes(5),
                                        rows.getString(6),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    fun clearDerivedSummaries() =
        transaction { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM objective_summary") }
        }

    fun packageRevisions(): List<InstalledPackage> =
        DriverManager.getConnection(jdbcUrl()).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """SELECT package_id, package_version, manifest_sha256, content_location, imported_at, active
                       FROM package_revision ORDER BY package_id, package_version""",
                    ).use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    InstalledPackage(
                                        rows.getString(1),
                                        rows.getString(2),
                                        rows.getString(3),
                                        rows.getString(4),
                                        rows.getString(5),
                                        rows.getInt(6) != 0,
                                    ),
                                )
                            }
                        }
                    }
            }
        }

    fun saveNote(note: StoredNote) =
        transaction { connection ->
            require(note.noteId.isNotBlank() && note.contentId.isNotBlank()) { "Note identity is required." }
            val updated =
                connection
                    .prepareStatement(
                        """UPDATE note SET content_id = ?, revision = ?, anchor = ?, text = ?, modified_at = ?
                           WHERE note_id = ?""",
                    ).use { statement ->
                        statement.setString(1, note.contentId)
                        statement.setString(2, note.revision)
                        statement.setString(3, note.anchor)
                        statement.setString(4, note.text)
                        statement.setString(5, note.modifiedAt)
                        statement.setString(6, note.noteId)
                        statement.executeUpdate()
                    }
            if (updated == 1) {
                updated
            } else {
                connection
                    .prepareStatement(
                        """INSERT INTO note(note_id, content_id, revision, anchor, text, created_at, modified_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    ).use { statement ->
                        statement.setString(1, note.noteId)
                        statement.setString(2, note.contentId)
                        statement.setString(3, note.revision)
                        statement.setString(4, note.anchor)
                        statement.setString(5, note.text)
                        statement.setString(6, note.createdAt)
                        statement.setString(7, note.modifiedAt)
                        statement.executeUpdate()
                    }
            }
        }

    fun notes(contentId: String? = null): List<StoredNote> =
        DriverManager.getConnection(jdbcUrl()).use { connection ->
            val sql =
                if (contentId == null) {
                    """SELECT note_id, content_id, revision, anchor, text, created_at, modified_at
                       FROM note ORDER BY created_at, note_id"""
                } else {
                    """SELECT note_id, content_id, revision, anchor, text, created_at, modified_at
                       FROM note WHERE content_id = ? ORDER BY created_at, note_id"""
                }
            connection.prepareStatement(sql).use { statement ->
                if (contentId != null) statement.setString(1, contentId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                StoredNote(
                                    rows.getString(1),
                                    rows.getString(2),
                                    rows.getString(3),
                                    rows.getString(4),
                                    rows.getString(5),
                                    rows.getString(6),
                                    rows.getString(7),
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun createSession(
        session: StoredSession,
        initialEvent: EventWrite? = null,
        initialResultVersion: Int? = null,
        initialResultPayload: ByteArray? = null,
    ) = transaction { connection ->
        connection.prepareStatement("SELECT active FROM package_revision WHERE package_id = ? AND package_version = ?").use {
            it.setString(1, session.revision.packageId.value)
            it.setString(2, session.revision.packageVersion.toString())
            it.executeQuery().use { rows -> require(rows.next() && rows.getInt(1) == 1) { "Package is not active for new sessions." } }
        }
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
        require(
            (initialEvent == null) == (initialResultVersion == null) &&
                (initialEvent == null) == (initialResultPayload == null),
        ) {
            "Initial event and command receipt must be supplied together."
        }
        initialEvent?.let { event ->
            require(event.commandId == "start:${session.sessionId}") { "Initial event command ID is invalid." }
            connection
                .prepareStatement(
                    """INSERT INTO command_result(
                           command_id, session_id, result_version, result_payload, committed_at
                           ) VALUES (?, ?, ?, ?, ?)""",
                ).use { statement ->
                    statement.setString(1, event.commandId)
                    statement.setString(2, session.sessionId)
                    statement.setInt(3, requireNotNull(initialResultVersion))
                    statement.setBytes(4, requireNotNull(initialResultPayload))
                    statement.setString(5, event.occurrence)
                    statement.executeUpdate()
                }
            insertEvent(connection, session.sessionId, event)
        }
    }

    fun loadSession(sessionId: String): StoredSession? =
        DriverManager.getConnection(jdbcUrl()).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            connection
                .prepareStatement(
                    """SELECT package_id, package_version, unit_id, unit_version, status,
                   current_activity_id, checkpoint, updated_at FROM learner_session WHERE session_id = ?""",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) return@use null
                        StoredSession(
                            sessionId,
                            UnitRevisionRef(
                                StableId(rows.getString(1)),
                                requireNotNull(Revision.parse(rows.getString(2))) {
                                    "Stored package version is invalid."
                                },
                                StableId(rows.getString(3)),
                                requireNotNull(Revision.parse(rows.getString(4))) {
                                    "Stored unit version is invalid."
                                },
                            ),
                            rows.getString(5),
                            rows.getString(6),
                            rows.getBytes(7),
                            rows.getString(8),
                        )
                    }
                }
        }

    fun isEmpty(): Boolean =
        transaction { connection ->
            SNAPSHOT_TABLES.all { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT EXISTS(SELECT 1 FROM $table LIMIT 1)").use { rows ->
                        rows.next() && rows.getInt(1) == 0
                    }
                }
            }
        }

    /** Returns a consistent application-level JSON snapshot; package files and secrets are excluded. */
    fun exportSnapshot(): ByteArray =
        transaction { connection ->
            val tables =
                SNAPSHOT_TABLES.associateWith { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT * FROM $table").use { rows ->
                            val metadata = rows.metaData
                            buildList {
                                while (rows.next()) {
                                    add(
                                        (1..metadata.columnCount).associate { index ->
                                            metadata.getColumnName(index) to sqlValue(rows, index)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            SnapshotJson.encode(tables).also {
                require(it.size <= MAX_BACKUP_BYTES) { "Backup exceeds the supported size limit." }
            }
        }

    /** Validates the entire snapshot before atomically inserting it into an empty profile. */
    fun restoreSnapshot(bytes: ByteArray): List<InstalledPackage> {
        require(bytes.size <= MAX_BACKUP_BYTES) { "Backup exceeds the supported size limit." }
        val rows = SnapshotJson.decode(bytes)
        transaction { connection ->
            val nonempty =
                SNAPSHOT_TABLES.any { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT EXISTS(SELECT 1 FROM $table LIMIT 1)").use { result ->
                            result.next() && result.getInt(1) != 0
                        }
                    }
                }
            require(!nonempty) { "Restore requires an empty learner profile." }
            validateSnapshotRows(rows)
            connection.createStatement().use { it.execute("PRAGMA defer_foreign_keys = ON") }
            // Parents precede children. Derived projections are imported as data and can be rebuilt later.
            SNAPSHOT_TABLES.forEach { table ->
                rows.getValue(table).forEach { row ->
                    val columns = row.keys.sorted()
                    val placeholders = columns.joinToString(",") { "?" }
                    connection
                        .prepareStatement("INSERT INTO $table (${columns.joinToString(",")}) VALUES ($placeholders)")
                        .use { statement ->
                            columns.forEachIndexed { index, column -> statement.setObject(index + 1, row[column]) }
                            statement.executeUpdate()
                        }
                }
            }
        }
        return packageRevisions().filter { installed ->
            !runCatching {
                val descriptor = PackageArchiveReader().inspect(Paths.get(installed.contentLocation))
                descriptor.packageId == installed.packageId && descriptor.version == installed.packageVersion &&
                    descriptor.manifestSha256 == installed.manifestSha256
            }.getOrDefault(false)
        }
    }

    private fun validateSnapshotRows(rows: Map<String, List<Map<String, Any?>>>) {
        require(rows.keys == SNAPSHOT_TABLES.toSet()) { "Backup table set is unsupported." }
        rows.forEach { (table, records) ->
            require(records.size <= MAX_BACKUP_ROWS) { "Backup contains too many $table records." }
            val expected = TABLE_COLUMNS.getValue(table)
            records.forEach { record ->
                require(record.keys == expected) { "Backup has an invalid $table record shape." }
                if (table == "package_revision") {
                    require(record["manifest_sha256"] is String && Regex("[0-9a-f]{64}").matches(record["manifest_sha256"] as String)) {
                        "Backup package hash is invalid."
                    }
                    require(
                        record["content_location"] is String &&
                            runCatching {
                                Paths.get(record["content_location"] as String)
                            }.isSuccess,
                    ) { "Backup content location is invalid." }
                }
                record.forEach { (column, value) ->
                    when {
                        value == null -> {
                            require(column in NULLABLE_COLUMNS[table].orEmpty()) {
                                "Backup has a missing required $table.$column value."
                            }
                        }

                        column in BLOB_COLUMNS[table].orEmpty() -> {
                            require(value is ByteArray && value.size <= MAX_BACKUP_BYTES) {
                                "Backup binary field $table.$column is invalid."
                            }
                        }

                        column in INTEGER_COLUMNS[table].orEmpty() -> {
                            require(value is Number) {
                                "Backup numeric field $table.$column is invalid."
                            }
                        }

                        else -> {
                            require(value is String && value.length <= MAX_BACKUP_TEXT) {
                                "Backup text field $table.$column is invalid."
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sqlValue(
        rows: ResultSet,
        index: Int,
    ): Any? {
        val value = rows.getObject(index) ?: return null
        return if (value is ByteArray) value.copyOf() else value
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
            require(commit.attempts.all { it.commandId == commandId }) {
                "Attempts must belong to the committed command."
            }
            require(commit.events.all { it.commandId == commandId }) { "Events must belong to the committed command." }
            val attemptIds = commit.attempts.map { it.id }.toSet()
            require(commit.observations.all { it.attemptId in attemptIds }) {
                "Observations must refer to attempts created by the committed command."
            }
            require(commit.observations.all { it.policyVersion > 0 }) { "Observation policy version must be positive." }
            fault("before-checkpoint")
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
            fault("checkpoint")
            check(sessionUpdated == 1) { "Session '$sessionId' is unavailable." }

            connection
                .prepareStatement(
                    """INSERT INTO command_result(
                       command_id, session_id, result_version, result_payload, committed_at
                       ) VALUES (?, ?, ?, ?, ?)""",
                ).use { statement ->
                    statement.setString(1, commandId)
                    statement.setString(2, sessionId)
                    statement.setInt(3, commit.resultVersion)
                    statement.setBytes(4, commit.resultPayload)
                    statement.setString(5, commit.updatedAt)
                    statement.executeUpdate()
                }
            fault("receipt")
            commit.draft?.let { saveDraft(connection, sessionId, it) }
            fault("draft")
            commit.attempts.forEach { insertAttempt(connection, sessionId, it) }
            fault("attempts")
            commit.observations.forEach { insertObservation(connection, it) }
            fault("observations")
            commit.events.forEach { insertEvent(connection, sessionId, it) }
            fault("events")
            commit.invalidatedObjectives.forEach { objectiveId ->
                connection
                    .prepareStatement("DELETE FROM objective_summary WHERE objective_id = ? AND unit_revision_key = ?")
                    .use { statement ->
                        statement.setString(1, objectiveId)
                        statement.setString(2, commit.unitRevisionKey)
                        statement.executeUpdate()
                    }
            }
            fault("projections")
            CommandReceipt(commandId, sessionId, commit.resultVersion, commit.resultPayload, wasReplay = false)
        }

    private fun saveDraft(
        connection: Connection,
        sessionId: String,
        draft: DraftWrite,
    ) {
        val updated =
            connection
                .prepareStatement(
                    """UPDATE draft SET text = ?, revision = ?, modified_at = ?
                       WHERE session_id = ? AND activity_id = ? AND revision < ?""",
                ).use { statement ->
                    statement.setString(1, draft.text)
                    statement.setLong(2, draft.revision)
                    statement.setString(3, draft.modifiedAt)
                    statement.setString(4, sessionId)
                    statement.setString(5, draft.activityId)
                    statement.setLong(6, draft.revision)
                    statement.executeUpdate()
                }
        if (updated == 1) return
        val inserted =
            connection
                .prepareStatement(
                    """INSERT INTO draft(session_id, activity_id, text, revision, modified_at)
                       SELECT ?, ?, ?, ?, ?
                       WHERE NOT EXISTS (SELECT 1 FROM draft WHERE session_id = ? AND activity_id = ?)""",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, draft.activityId)
                    statement.setString(3, draft.text)
                    statement.setLong(4, draft.revision)
                    statement.setString(5, draft.modifiedAt)
                    statement.setString(6, sessionId)
                    statement.setString(7, draft.activityId)
                    statement.executeUpdate()
                }
        check(inserted == 1) { "Draft revision is stale; the newer saved draft was preserved." }
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
               response, outcome, method, assistance, submitted_at, local_study_date, zone_offset, context_snapshot
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
                statement.setString(14, attempt.contextSnapshot)
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

    private fun insertObservation(
        connection: Connection,
        observation: ObservationWrite,
    ) {
        connection
            .prepareStatement(
                """INSERT INTO observation(
               attempt_id, objective_id, skill, assessment_family, policy_version
               ) VALUES (?, ?, ?, ?, ?)""",
            ).use { statement ->
                statement.setString(1, observation.attemptId)
                statement.setString(2, observation.objectiveId)
                statement.setString(3, observation.skill)
                statement.setString(4, observation.assessmentFamily)
                statement.setInt(5, observation.policyVersion)
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
                fault("before-commit")
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
        require(version <= SCHEMA_VERSION) {
            "Database schema $version is newer than supported schema $SCHEMA_VERSION."
        }
        if (version == SCHEMA_VERSION) return
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            if (version == 0) SCHEMA_V1.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
            SCHEMA_V2.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
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
        const val SCHEMA_VERSION = 2
        const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
        const val MAX_BACKUP_ROWS = 250_000
        const val MAX_BACKUP_TEXT = 2_000_000
        val SNAPSHOT_TABLES =
            listOf(
                "package_revision",
                "learner_session",
                "command_result",
                "draft",
                "attempt",
                "learning_event",
                "observation",
                "objective_summary",
                "review_task",
                "note",
            )
        val TABLE_COLUMNS =
            mapOf(
                "package_revision" to
                    setOf(
                        "package_id",
                        "package_version",
                        "manifest_sha256",
                        "content_location",
                        "imported_at",
                        "active",
                    ),
                "learner_session" to
                    setOf(
                        "session_id",
                        "package_id",
                        "package_version",
                        "unit_id",
                        "unit_version",
                        "status",
                        "current_activity_id",
                        "checkpoint",
                        "updated_at",
                    ),
                "command_result" to setOf("command_id", "session_id", "result_version", "result_payload", "committed_at"),
                "draft" to setOf("session_id", "activity_id", "text", "revision", "modified_at"),
                "attempt" to
                    setOf(
                        "attempt_id",
                        "command_id",
                        "session_id",
                        "unit_revision_key",
                        "activity_id",
                        "sequence",
                        "response",
                        "outcome",
                        "method",
                        "assistance",
                        "submitted_at",
                        "local_study_date",
                        "zone_offset",
                        "context_snapshot",
                    ),
                "learning_event" to
                    setOf(
                        "sequence",
                        "event_id",
                        "command_id",
                        "session_id",
                        "package_id",
                        "package_version",
                        "unit_id",
                        "unit_version",
                        "event_type",
                        "payload_version",
                        "occurrence",
                        "activity_id",
                        "payload",
                    ),
                "observation" to setOf("attempt_id", "objective_id", "skill", "assessment_family", "policy_version"),
                "objective_summary" to
                    setOf(
                        "objective_id",
                        "unit_revision_key",
                        "state",
                        "supporting_attempts",
                        "policy_version",
                    ),
                "review_task" to
                    setOf(
                        "objective_id",
                        "unit_revision_key",
                        "policy_version",
                        "stage",
                        "due_date",
                        "last_assessment_family",
                        "status",
                    ),
                "note" to setOf("note_id", "content_id", "revision", "anchor", "text", "created_at", "modified_at"),
            )
        val BLOB_COLUMNS =
            mapOf(
                "learner_session" to setOf("checkpoint"),
                "command_result" to setOf("result_payload"),
                "attempt" to setOf("assistance"),
                "learning_event" to setOf("payload"),
                "objective_summary" to setOf("supporting_attempts"),
            )
        val INTEGER_COLUMNS =
            mapOf(
                "package_revision" to setOf("active"),
                "command_result" to setOf("result_version"),
                "draft" to setOf("revision"),
                "attempt" to setOf("sequence"),
                "learning_event" to setOf("sequence", "payload_version"),
                "observation" to setOf("policy_version"),
                "objective_summary" to setOf("policy_version"),
                "review_task" to setOf("policy_version", "stage"),
            )
        val NULLABLE_COLUMNS =
            mapOf(
                "learning_event" to setOf("activity_id"),
                "observation" to setOf("assessment_family"),
                "review_task" to setOf("last_assessment_family"),
                "note" to setOf("revision"),
            )
        val SCHEMA_V2 =
            listOf(
                "ALTER TABLE package_revision ADD COLUMN active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0, 1))",
                "ALTER TABLE attempt ADD COLUMN context_snapshot TEXT NOT NULL DEFAULT 'Context unavailable in schema 1'",
            )
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
    val active: Boolean = true,
)

data class StoredNote(
    val noteId: String,
    val contentId: String,
    val revision: String?,
    val anchor: String,
    val text: String,
    val createdAt: String,
    val modifiedAt: String,
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
    val contextSnapshot: String = "Context unavailable",
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

data class ObservationWrite(
    val attemptId: String,
    val objectiveId: String,
    val skill: String,
    val assessmentFamily: String?,
    val policyVersion: Int,
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
    val observations: List<ObservationWrite> = emptyList(),
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

/** A readable historical record, independent of installed packages. */
data class HistoricalAttempt(
    val id: String,
    val response: String,
    val outcome: String,
    val method: String,
    val assistance: ByteArray,
    val contextSnapshot: String,
)
