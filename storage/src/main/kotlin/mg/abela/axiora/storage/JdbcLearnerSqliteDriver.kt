package mg.abela.axiora.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

internal class JdbcLearnerSqliteDriver(
    database: Path,
) : LearnerSqliteDriver {
    private val url = "jdbc:sqlite:${database.toAbsolutePath()}"

    init {
        database.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    }

    override fun <T> read(block: (LearnerSqliteConnection) -> T): T =
        DriverManager.getConnection(url).use { connection ->
            val sql = JdbcLearnerConnection(connection)
            sql.execute("PRAGMA foreign_keys = ON")
            sql.execute("PRAGMA busy_timeout = 5000")
            block(sql)
        }

    override fun <T> transaction(block: (LearnerSqliteConnection) -> T): T =
        read { connection ->
            connection.execute("BEGIN IMMEDIATE")
            try {
                val result = block(connection)
                connection.execute("COMMIT")
                result
            } catch (error: Throwable) {
                runCatching { connection.execute("ROLLBACK") }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
}

private class JdbcLearnerConnection(
    private val connection: Connection,
) : LearnerSqliteConnection {
    override fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun update(
        sql: String,
        vararg arguments: Any?,
    ): Int =
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }

    override fun query(
        sql: String,
        vararg arguments: String,
    ): LearnerSqliteRows {
        val statement = connection.prepareStatement(sql)
        try {
            arguments.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            return JdbcLearnerRows(statement, statement.executeQuery())
        } catch (error: Throwable) {
            runCatching(statement::close).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}

private class JdbcLearnerRows(
    private val statement: PreparedStatement,
    private val rows: ResultSet,
) : LearnerSqliteRows {
    override val columnCount: Int get() = rows.metaData.columnCount

    override fun columnName(index: Int): String = rows.metaData.getColumnName(index)

    override fun next(): Boolean = rows.next()

    override fun getNullableString(index: Int): String? = rows.getString(index)

    override fun getInt(index: Int): Int = rows.getInt(index)

    override fun getBytes(index: Int): ByteArray = rows.getBytes(index)

    override fun value(index: Int): Any? {
        val value = rows.getObject(index)
        return if (value is ByteArray) value.copyOf() else value
    }

    override fun close() {
        statement.use { rows.close() }
    }
}
