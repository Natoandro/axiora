package mg.abela.axiora.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mg.abela.axiora.validation.ContentValidator
import mg.abela.axiora.validation.PackageArchiveAccess
import mg.abela.axiora.validation.PackageRevisionDescriptor
import mg.abela.axiora.validation.ValidationResult
import java.nio.file.Files
import java.nio.file.Path

/** JNI adapter. Rust validates and loads bytes; Kotlin owns staging lifetime and content models. */
internal class RustPackageArchiveReader(
    private val cacheDirectory: Path,
) : PackageArchiveAccess {
    private val mapper = ObjectMapper()

    init {
        System.loadLibrary("axiora_package")
    }

    override fun inspect(archive: Path): PackageRevisionDescriptor {
        Files.createDirectories(cacheDirectory)
        return descriptor(
            response(
                nativeInspect(
                    archive.toAbsolutePath().normalize().toString(),
                    cacheDirectory.toAbsolutePath().normalize().toString(),
                ),
            ),
        )
    }

    override fun <T> load(
        archive: Path,
        consume: (PackageRevisionDescriptor, Path, ValidationResult) -> T,
    ): T {
        Files.createDirectories(cacheDirectory)
        val staging = Files.createTempDirectory(cacheDirectory, "axiora-package-")
        try {
            val result =
                response(
                    nativeLoad(
                        archive.toAbsolutePath().normalize().toString(),
                        staging.toAbsolutePath().normalize().toString(),
                    ),
                )
            val packageDescriptor = descriptor(result)
            val validation = ContentValidator().validate(staging)
            require(validation.valid) {
                validation.diagnostics.joinToString("\n") {
                    "${it.code} ${it.file}:${it.path}: ${it.message}"
                }
            }
            return consume(packageDescriptor, staging, validation)
        } finally {
            Files.walk(staging).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun response(bytes: ByteArray): JsonNode {
        val value = mapper.readTree(bytes)
        check(value.path("ok").asBoolean(false)) {
            value.path("error").asText("Rust package operation failed.")
        }
        return value.path("descriptor")
    }

    private fun descriptor(node: JsonNode): PackageRevisionDescriptor =
        PackageRevisionDescriptor(
            packageId = node.path("package_id").asText(),
            version = node.path("version").asText(),
            manifestSha256 = node.path("manifest_sha256").asText(),
            archiveSha256 = node.path("archive_sha256").asText(),
            payloadCount = node.path("payload_count").asInt(),
            uncompressedBytes = node.path("uncompressed_bytes").asLong(),
        )

    private external fun nativeInspect(path: String, scratch: String): ByteArray

    private external fun nativeLoad(path: String, staging: String): ByteArray
}
