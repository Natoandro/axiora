package mg.abela.axiora.storage.android

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import mg.abela.axiora.storage.LearnerSqliteConnection
import mg.abela.axiora.storage.LearnerSqliteDriver
import mg.abela.axiora.storage.LearnerSqliteRows
import java.io.File

/** Keep one driver per learner database. Run storage calls off the UI thread. */
class AndroidLearnerSqliteDriver(
    file: File,
) : LearnerSqliteDriver,
    AutoCloseable {
    private val database: SQLiteDatabase

    init {
        file.absoluteFile.parentFile?.mkdirs()
        val opened =
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ) {
                // Android's default handler can delete the database on corruption.
                throw SQLiteDatabaseCorruptException("Learner database is corrupt.")
            }
        try {
            opened.setForeignKeyConstraintsEnabled(true)
            database = opened
        } catch (error: Throwable) {
            runCatching(opened::close).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun <T> read(block: (LearnerSqliteConnection) -> T): T = block(AndroidLearnerConnection(database))

    override fun <T> transaction(block: (LearnerSqliteConnection) -> T): T {
        database.beginTransactionNonExclusive()
        try {
            val result = read(block)
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    override fun close() = database.close()
}

private class AndroidLearnerConnection(
    private val database: SQLiteDatabase,
) : LearnerSqliteConnection {
    override fun execute(sql: String) = database.execSQL(sql)

    override fun update(
        sql: String,
        vararg arguments: Any?,
    ): Int =
        database.compileStatement(sql).use { statement ->
            arguments.forEachIndexed { index, value ->
                val parameter = index + 1
                when (value) {
                    null -> statement.bindNull(parameter)
                    is String -> statement.bindString(parameter, value)
                    is Int -> statement.bindLong(parameter, value.toLong())
                    is Long -> statement.bindLong(parameter, value)
                    is ByteArray -> statement.bindBlob(parameter, value)
                    else -> error("Unsupported SQLite bind value type.")
                }
            }
            statement.executeUpdateDelete()
        }

    override fun query(
        sql: String,
        vararg arguments: String,
    ): LearnerSqliteRows = AndroidLearnerRows(database.rawQuery(sql, arguments))
}

private class AndroidLearnerRows(
    private val cursor: Cursor,
) : LearnerSqliteRows {
    override val columnCount: Int get() = cursor.columnCount

    override fun columnName(index: Int): String = cursor.getColumnName(index - 1)

    override fun next(): Boolean = cursor.moveToNext()

    override fun getNullableString(index: Int): String? = cursor.getString(index - 1)

    override fun getInt(index: Int): Int = cursor.getInt(index - 1)

    override fun getBytes(index: Int): ByteArray = cursor.getBlob(index - 1)

    override fun value(index: Int): Any? {
        val column = index - 1
        return when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
            else -> error("Unsupported SQLite column type.")
        }
    }

    override fun close() = cursor.close()
}
