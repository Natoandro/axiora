package mg.abela.axiora.storage

import mg.abela.axiora.validation.PackageArchiveWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PackageInstallerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `package promotion is idempotent and conflicting identity preserves installed archive`() {
        val store = SqliteLearnerStore(temp.resolve("learner.db"))
        val installer = PackageInstaller(temp.resolve("library"), store)
        val source = copyFixture(temp.resolve("source-a"))
        val firstArchive = temp.resolve("first.axp")
        PackageArchiveWriter().pack(source, firstArchive)

        val first = installer.install(firstArchive)
        val second = installer.install(firstArchive)
        assertFalse(first.alreadyInstalled)
        assertTrue(second.alreadyInstalled)
        assertEquals(1, store.packageRevisions().size)

        val conflictingSource = copyFixture(temp.resolve("source-b"))
        val manifestPath = conflictingSource.resolve("manifest.json")
        Files.writeString(
            manifestPath,
            Files.readString(manifestPath).replace("Prime and maximal ideals", "Prime and maximal ideals revised"),
        )
        val conflictingArchive = temp.resolve("conflicting.axp")
        PackageArchiveWriter().pack(conflictingSource, conflictingArchive)
        assertThrows(IllegalArgumentException::class.java) { installer.install(conflictingArchive) }

        val report = installer.reconcile()
        assertEquals(1, report.registeredOrFound)
        assertTrue(report.missingFiles.isEmpty())
        assertEquals(1, store.packageRevisions().size)
    }

    private fun copyFixture(target: Path): Path {
        val direct = Path.of("examples/prime-and-maximal").toAbsolutePath().normalize()
        val parent = Path.of("../examples/prime-and-maximal").toAbsolutePath().normalize()
        val source = if (Files.isDirectory(direct)) direct else parent
        Files.createDirectories(target)
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
            }
        }
        return target
    }
}
