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

    @Test
    fun `import boundary failures reconcile promoted bytes and clean incomplete staging`() {
        val source = copyFixture(temp.resolve("fault-source"))
        val archive = temp.resolve("fault.axp")
        PackageArchiveWriter().pack(source, archive)
        listOf("staging", "copied", "validated", "promoted", "registered").forEach { boundary ->
            val root = temp.resolve(boundary)
            val store = SqliteLearnerStore(root.resolve("learner.db"))
            val library = root.resolve("library")
            val broken = PackageInstaller(library, store, fault = { if (it == boundary) error("injected") })
            assertThrows(IllegalStateException::class.java) { broken.install(archive) }
            val recovered = PackageInstaller(library, SqliteLearnerStore(root.resolve("learner.db")))
            val report = recovered.reconcile()
            assertEquals(if (boundary in setOf("promoted", "registered")) 1 else 0, report.registeredOrFound)
            assertTrue(report.missingFiles.isEmpty())
            recovered.install(archive)
            assertEquals(1, store.packageRevisions().size)
            Files.walk(library).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".import-") }) }
        }
    }

    @Test
    fun `registration failure retains recoverable promoted archive`() {
        val source = copyFixture(temp.resolve("register-source"))
        val archive = temp.resolve("register.axp")
        PackageArchiveWriter().pack(source, archive)
        val db = temp.resolve("register.db")
        val brokenStore = SqliteLearnerStore(db) { if (it == "before-commit") error("database unavailable") }
        val library = temp.resolve("register-library")
        assertThrows(IllegalStateException::class.java) { PackageInstaller(library, brokenStore).install(archive) }
        val store = SqliteLearnerStore(db)
        assertTrue(store.packageRevisions().isEmpty())
        assertEquals(1, PackageInstaller(library, store).reconcile().registeredOrFound)
    }

    @Test
    fun `deactivation preserves archives notes and sessions across reconciliation and reimport`() {
        val source = copyFixture(temp.resolve("remove-source"))
        val archive = temp.resolve("remove.axp")
        PackageArchiveWriter().pack(source, archive)
        val store = SqliteLearnerStore(temp.resolve("remove.db"))
        val installer = PackageInstaller(temp.resolve("remove-library"), store)
        val installed = installer.install(archive)
        store.saveNote(StoredNote("note", "axiora.unit", null, "proof", "retained", "today", "today"))
        store.setPackageActive(installed.descriptor.packageId, installed.descriptor.version, false)
        installer.reconcile()
        assertTrue(store.activePackages().isEmpty())
        assertEquals("retained", store.notes().single().text)
        assertTrue(Files.isRegularFile(installed.archive))
        installer.install(archive)
        assertEquals(1, store.activePackages().size)
        Files.delete(installed.archive)
        assertEquals(1, installer.reconcile().missingFiles.size)
    }

    @Test
    fun `new package and unit versions install beside pinned old work`() {
        val source = copyFixture(temp.resolve("update-source"))
        val oldArchive = temp.resolve("old.axp")
        PackageArchiveWriter().pack(source, oldArchive)
        val store = SqliteLearnerStore(temp.resolve("update.db"))
        val installer = PackageInstaller(temp.resolve("update-library"), store)
        val old = installer.install(oldArchive)
        val revision =
            mg.abela.axiora.learning.UnitRevisionRef(
                mg.abela.axiora.content
                    .StableId(old.descriptor.packageId),
                mg.abela.axiora.content
                    .Revision(0, 1, 0),
                mg.abela.axiora.content
                    .StableId("axiora.ca.prime-vs-maximal"),
                mg.abela.axiora.content
                    .Revision(0, 1, 0),
            )
        store.createSession(StoredSession("pinned", revision, "ACTIVE", "entry", "old checkpoint".toByteArray(), "today"))
        val manifest = source.resolve("manifest.json")
        Files.writeString(manifest, Files.readString(manifest).replace("\"version\": \"0.1.0\"", "\"version\": \"0.2.0\""))
        Files.list(source.resolve("units")).use { paths ->
            paths.forEach { path ->
                Files.writeString(path, Files.readString(path).replace("\"version\": \"0.1.0\"", "\"version\": \"0.2.0\""))
            }
        }
        val mapper =
            com.fasterxml.jackson.databind
                .ObjectMapper()
        val updatedManifest = mapper.readTree(Files.readAllBytes(manifest))
        updatedManifest.path("files").forEach { entry ->
            val bytes = Files.readAllBytes(source.resolve(entry.path("path").asText()))
            val hash =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            (entry as com.fasterxml.jackson.databind.node.ObjectNode).put("bytes", bytes.size).put("sha256", hash)
        }
        Files.write(manifest, mapper.writeValueAsBytes(updatedManifest))
        val newArchive = temp.resolve("new.axp")
        PackageArchiveWriter().pack(source, newArchive)
        val updated = installer.install(newArchive)
        assertEquals("0.2.0", updated.descriptor.version)
        assertEquals(2, store.packageRevisions().size)
        assertEquals(revision, store.loadSession("pinned")!!.revision)
        assertTrue(Files.isRegularFile(old.archive))
        assertEquals(2, installer.reconcile().registeredOrFound)
        val abandoned = old.archive.parent.resolve(".import-crash.tmp")
        Files.writeString(abandoned, "partial")
        installer.reconcile()
        assertFalse(Files.exists(abandoned))
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
