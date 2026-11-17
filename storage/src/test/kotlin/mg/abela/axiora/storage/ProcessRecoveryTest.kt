package mg.abela.axiora.storage

import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId
import mg.abela.axiora.learning.UnitRevisionRef
import mg.abela.axiora.validation.PackageArchiveWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A separate JVM halts without finally blocks, closing the gap left by exception-only fault tests. */
class ProcessRecoveryTest {
    @TempDir
    lateinit var temp: Path

    private fun crash(vararg args: String) {
        val paths = mutableSetOf<String>()
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { paths += Path.of(it.toURI()).toString() }
            loader = loader.parent
        }
        paths += System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        val log = temp.resolve("crash-${args.first()}.log")
        val process =
            ProcessBuilder(
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    paths.joinToString(java.io.File.pathSeparator),
                    StorageCrashProbe::class.java.name,
                ) +
                    args,
            ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Crash probe timed out")
            assertEquals(73, process.exitValue(), Files.readString(log))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `process termination during draft transaction restores last commit`() {
        val db = temp.resolve("crash.db")
        val store = SqliteLearnerStore(db)
        store.registerPackage(InstalledPackage("axiora.example", "1.0.0", "a".repeat(64), "/missing", "today"))
        store.createSession(
            StoredSession(
                "session",
                UnitRevisionRef(StableId("axiora.example"), Revision(1, 0, 0), StableId("axiora.unit"), Revision(1, 0, 0)),
                "ACTIVE",
                "proof",
                "committed".toByteArray(),
                "today",
            ),
        )
        val before = store.exportSnapshot()
        crash("transaction", db.toString())
        assertArrayEquals(before, SqliteLearnerStore(db).exportSnapshot())
    }

    @Test
    fun `process termination after promotion is reconciled without duplicate installation`() {
        val fixture =
            listOf(
                Path.of("examples/prime-and-maximal"),
                Path.of("../examples/prime-and-maximal"),
            ).first { Files.isDirectory(it) }
        val archive = temp.resolve("course.axp")
        PackageArchiveWriter().pack(fixture, archive)
        val db = temp.resolve("import.db")
        val library = temp.resolve("library")
        crash("import", db.toString(), library.toString(), archive.toString())
        val store = SqliteLearnerStore(db)
        assertTrue(store.packageRevisions().isEmpty())
        val installer = PackageInstaller(library, store)
        assertEquals(1, installer.reconcile().registeredOrFound)
        assertTrue(installer.install(archive).alreadyInstalled)
        assertEquals(1, store.packageRevisions().size)
    }
}

object StorageCrashProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val db = Path.of(args[1])
        if (args[0] == "transaction") {
            SqliteLearnerStore(db, fault = { if (it == "draft") Runtime.getRuntime().halt(73) }).executeCommand("session", "unsaved") {
                CommandCommit(
                    "session",
                    "unsaved",
                    1,
                    "unsaved".toByteArray(),
                    "ACTIVE",
                    "proof",
                    "unsaved".toByteArray(),
                    "today",
                    "revision",
                    draft = DraftWrite("proof", "unsaved text", 1, "today"),
                )
            }
        } else {
            PackageInstaller(Path.of(args[2]), SqliteLearnerStore(db), fault = {
                if (it ==
                    "promoted"
                ) {
                    Runtime.getRuntime().halt(73)
                }
            }).install(Path.of(args[3]))
        }
        error("Crash boundary was not reached")
    }
}
