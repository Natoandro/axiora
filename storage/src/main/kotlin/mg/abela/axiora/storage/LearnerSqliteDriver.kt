package mg.abela.axiora.storage

/** Owns connection configuration and transaction boundaries. A write transaction must serialize writers. */
interface LearnerSqliteDriver {
    fun <T> read(block: (LearnerSqliteConnection) -> T): T

    fun <T> transaction(block: (LearnerSqliteConnection) -> T): T
}

/** Queries bind text keys. Writes also bind integers, blobs and SQL NULL. */
interface LearnerSqliteConnection {
    fun execute(sql: String)

    fun update(
        sql: String,
        vararg arguments: Any?,
    ): Int

    fun query(
        sql: String,
        vararg arguments: String,
    ): LearnerSqliteRows
}

/** One-based column indices. Rows must be consumed and closed within the driver callback. */
interface LearnerSqliteRows : AutoCloseable {
    val columnCount: Int

    fun columnName(index: Int): String

    fun next(): Boolean

    fun getNullableString(index: Int): String?

    fun getString(index: Int): String = checkNotNull(getNullableString(index))

    fun getInt(index: Int): Int

    fun getBytes(index: Int): ByteArray

    fun value(index: Int): Any?
}
