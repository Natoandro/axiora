package mg.abela.axiora.storage

import mg.abela.axiora.validation.PackageArchiveReader
import mg.abela.axiora.validation.PackageLimits
import mg.abela.axiora.validation.PackageRevisionDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Instant

/** Installs only reader-validated immutable archives and reconciles filesystem/database crash gaps. */
class PackageInstaller(
    private val library: Path,
    private val store: SqliteLearnerStore,
    private val reader: PackageArchiveReader = PackageArchiveReader(),
    private val now: () -> Instant = Instant::now,
    private val fault: (String) -> Unit = {},
) {
    private val packages = library.resolve("packages")

    fun install(archive: Path): PackageInstallResult {
        val descriptor = reader.inspect(archive)
        val registered = store.packageRevisions().find { it.packageId == descriptor.packageId && it.packageVersion == descriptor.version }
        require(registered == null || registered.manifestSha256 == descriptor.manifestSha256) {
            "Different package bytes are registered for ${descriptor.packageId}@${descriptor.version}."
        }
        val directory = revisionDirectory(descriptor)
        Files.createDirectories(packages)
        val packageDirectory = directory.parent
        require(
            !Files.isSymbolicLink(packages) &&
                !Files.isSymbolicLink(packageDirectory) &&
                !Files.isSymbolicLink(directory),
        ) {
            "Package library path cannot be a symbolic link."
        }
        Files.createDirectories(packageDirectory)
        Files.createDirectories(directory)
        val installedArchive = directory.resolve("content.axp")
        if (Files.exists(installedArchive)) {
            val existing = reader.inspect(installedArchive)
            require(existing.manifestSha256 == descriptor.manifestSha256) {
                "Different package bytes already exist for ${descriptor.packageId}@${descriptor.version}."
            }
            store.registerPackage(record(descriptor, installedArchive))
            store.setPackageActive(descriptor.packageId, descriptor.version, true)
            return PackageInstallResult(descriptor, installedArchive, alreadyInstalled = true)
        }

        val staging = Files.createTempFile(directory, ".import-", ".tmp")
        try {
            fault("staging")
            copyBounded(archive, staging)
            fault("copied")
            val stagedDescriptor = reader.inspect(staging)
            require(
                stagedDescriptor.manifestSha256 == descriptor.manifestSha256 &&
                    stagedDescriptor.archiveSha256 == descriptor.archiveSha256,
            ) {
                "Package changed while it was being imported."
            }
            fault("validated")
            Files.move(staging, installedArchive, ATOMIC_MOVE)
            fault("promoted")
            store.registerPackage(record(descriptor, installedArchive))
            store.setPackageActive(descriptor.packageId, descriptor.version, true)
            fault("registered")
            return PackageInstallResult(descriptor, installedArchive, alreadyInstalled = false)
        } finally {
            // Promoted archives remain recoverable by reconciliation after registration failure.
            Files.deleteIfExists(staging)
        }
    }

    /** Repairs the only cross-resource gap: a promoted archive whose database commit did not finish. */
    fun reconcile(): ReconciliationReport {
        Files.createDirectories(packages)
        require(!Files.isSymbolicLink(packages)) { "Package library path cannot be a symbolic link." }
        val discovered = mutableListOf<InstalledPackage>()
        Files.walk(packages).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Package library cannot contain symbolic links." }
                if (Files.isRegularFile(path) && path.fileName.toString().startsWith(".import-") &&
                    path.fileName.toString().endsWith(".tmp")
                ) {
                    Files.delete(path)
                    return@forEach
                }
                if (!Files.isRegularFile(path) || path.fileName.toString() != "content.axp") return@forEach
                val descriptor = reader.inspect(path)
                val expected = revisionDirectory(descriptor).resolve("content.axp").toAbsolutePath().normalize()
                require(path.toAbsolutePath().normalize() == expected) {
                    "Installed package is in an unexpected location: $path"
                }
                val record = record(descriptor, path)
                store.registerPackage(record)
                discovered += record
            }
        }
        val installedPaths = discovered.map { Paths.get(it.contentLocation).toAbsolutePath().normalize() }.toSet()
        val missing =
            store.packageRevisions().filter {
                Paths.get(it.contentLocation).toAbsolutePath().normalize() !in installedPaths
            }
        return ReconciliationReport(discovered.size, missing)
    }

    private fun revisionDirectory(descriptor: PackageRevisionDescriptor): Path {
        require(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*").matches(descriptor.packageId)) {
            "Package ID is unsafe for a library path."
        }
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(descriptor.version)) {
            "Package version is unsafe for a library path."
        }
        return packages.resolve(descriptor.packageId).resolve(descriptor.version)
    }

    private fun record(
        descriptor: PackageRevisionDescriptor,
        archive: Path,
    ) = InstalledPackage(
        descriptor.packageId,
        descriptor.version,
        descriptor.manifestSha256,
        archive.toAbsolutePath().normalize().toString(),
        now().toString(),
    )

    private fun copyBounded(
        source: Path,
        target: Path,
    ) {
        val limit = PackageLimits.ARCHIVE_BYTES.toLong()
        require(Files.size(source) <= limit) { "Package archive exceeds the supported size limit." }
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    require(copied <= limit) { "Package archive exceeds the supported size limit." }
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}

data class PackageInstallResult(
    val descriptor: PackageRevisionDescriptor,
    val archive: Path,
    val alreadyInstalled: Boolean,
)

data class ReconciliationReport(
    val registeredOrFound: Int,
    val missingFiles: List<InstalledPackage>,
)
