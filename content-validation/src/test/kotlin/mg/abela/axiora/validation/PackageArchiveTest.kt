package mg.abela.axiora.validation

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackageArchiveTest {
    private val fixture = Path.of("../examples/prime-and-maximal").toAbsolutePath().normalize()

    @Test fun `pack is reproducible and inspect validates revision`() {
        val directory = Files.createTempDirectory("axiora-pack-test")
        try {
            val sourceManifestBefore = Files.readAllBytes(fixture.resolve("manifest.json"))
            val first = directory.resolve("first.axp")
            val second = directory.resolve("second.axp")
            val writer = PackageArchiveWriter()
            val built1 = writer.pack(fixture, first)
            val built2 = writer.pack(fixture, second)
            assertEquals(Files.readAllBytes(first).toList(), Files.readAllBytes(second).toList())
            assertEquals(built1.descriptor.archiveSha256, built2.descriptor.archiveSha256)
            val inspected = PackageArchiveReader().inspect(first)
            assertEquals("axiora.ca.first-slice", inspected.packageId)
            assertEquals("0.1.0", inspected.version)
            assertEquals(built1.descriptor.manifestSha256, inspected.manifestSha256)
            assertEquals(4, inspected.payloadCount)
            assertTrue(sourceManifestBefore.contentEquals(Files.readAllBytes(fixture.resolve("manifest.json"))))
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `Kotlin reader opens the Rust-produced compatibility archive`() {
        val rustArchive =
            Path
                .of("../crates/axiora-package/tests/fixtures/rust-reference/prime-and-maximal.axp")
                .toAbsolutePath()
                .normalize()
        val descriptor = PackageArchiveReader().inspect(rustArchive)
        assertEquals("axiora.ca.first-slice", descriptor.packageId)
        assertEquals("0.1.0", descriptor.version)
        assertEquals(
            "8fdd449a61de27a54467c45eda95b6fc366cc7cbe4ceb0b95c5670342706ebad",
            descriptor.manifestSha256,
        )
        assertEquals(
            "650159843d7e7d8aad649695eb54a0b3255182df59972a1364525530935dec4a",
            descriptor.archiveSha256,
        )
    }

    @Test fun `pack refuses existing output unless overwrite is explicit`() {
        val directory = Files.createTempDirectory("axiora-overwrite-test")
        try {
            val output = directory.resolve("sample.axp")
            PackageArchiveWriter().pack(fixture, output)
            val originalHash = PackageArchiveReader().inspect(output).archiveSha256
            assertFailsWith<IllegalArgumentException> { PackageArchiveWriter().pack(fixture, output) }
            PackageArchiveWriter().pack(fixture, output, overwrite = true)
            assertEquals(originalHash, PackageArchiveReader().inspect(output).archiveSha256)
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `inspect rejects traversal before staging`() {
        val directory = Files.createTempDirectory("axiora-hostile-test")
        try {
            val archive = directory.resolve("traversal.axp")
            ZipOutputStream(Files.newOutputStream(archive), Charsets.UTF_8).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
            val error = assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(archive) }
            assertTrue(error.message.orEmpty().contains("Unsafe archive path"))
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `inspect rejects symlink metadata and case colliding paths`() {
        val directory = Files.createTempDirectory("axiora-metadata-test")
        try {
            val symlink = directory.resolve("symlink.axp")
            PackageArchiveWriter().pack(fixture, symlink)
            mutateArchive(symlink) { bytes ->
                val central = centralRecord(bytes, "manifest.json")
                bytes[central + 5] = 3
                put16(bytes, central + 40, 0xa1ff)
            }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(symlink) }

            val collision = directory.resolve("collision.axp")
            PackageArchiveWriter().pack(fixture, collision)
            val lower = "units/prerequisite-recognition.json".toByteArray()
            val upper = "Units/prerequisite-recognition.json".toByteArray()
            mutateArchive(collision) { bytes ->
                val central = centralRecord(bytes, lower.toString(Charsets.UTF_8))
                val local = get32(bytes, central + 42).toInt()
                val positions = listOf(local + 30, central + 46)
                assertEquals(2, positions.size)
                positions.forEach { position -> bytes[position] = upper[0] }
            }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(collision) }
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `inspect rejects encrypted entries malformed names and metadata mismatches`() {
        val directory = Files.createTempDirectory("axiora-header-test")
        try {
            val encrypted = directory.resolve("encrypted.axp")
            PackageArchiveWriter().pack(fixture, encrypted)
            mutateArchive(encrypted) { bytes ->
                val central = centralRecord(bytes, "manifest.json")
                val local = get32(bytes, central + 42).toInt()
                put16(bytes, central + 8, get16(bytes, central + 8) or 1)
                put16(bytes, local + 6, get16(bytes, local + 6) or 1)
            }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(encrypted) }

            val malformed = directory.resolve("malformed-name.axp")
            PackageArchiveWriter().pack(fixture, malformed)
            mutateArchive(malformed) { bytes ->
                val central = centralRecord(bytes, "manifest.json")
                val local = get32(bytes, central + 42).toInt()
                val nameOffset = local + 30
                val centralName = central + 46
                bytes[nameOffset] = 0xff.toByte()
                bytes[centralName] = 0xff.toByte()
            }
            assertFailsWith<Exception> { PackageArchiveReader().inspect(malformed) }

            val mismatch = directory.resolve("size-mismatch.axp")
            PackageArchiveWriter().pack(fixture, mismatch)
            mutateArchive(mismatch) { bytes ->
                val central = centralRecord(bytes, "manifest.json")
                put32(bytes, central + 24, get32(bytes, central + 24) + 1)
            }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(mismatch) }
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `inspect rejects corrupt payload bytes and oversized archives`() {
        val directory = Files.createTempDirectory("axiora-limits-test")
        try {
            val corrupt = directory.resolve("corrupt.axp")
            PackageArchiveWriter().pack(fixture, corrupt)
            mutateArchive(corrupt) { bytes ->
                val central = centralRecord(bytes, "concepts/core.json")
                val local = get32(bytes, central + 42).toInt()
                val data = local + 30 + get16(bytes, local + 26) + get16(bytes, local + 28)
                bytes[data] = (bytes[data].toInt() xor 1).toByte()
            }
            assertFailsWith<Exception> { PackageArchiveReader().inspect(corrupt) }

            val oversized = directory.resolve("oversized.axp")
            Files
                .newByteChannel(
                    oversized,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.truncate(PackageLimits.ARCHIVE_BYTES.toLong() + 1)
                    channel.position(PackageLimits.ARCHIVE_BYTES.toLong())
                    channel.write(ByteBuffer.wrap(byteArrayOf(0)))
                }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(oversized) }
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `inspect rejects duplicate names unknown capability corrupt hashes and undeclared files`() {
        val directory = Files.createTempDirectory("axiora-inventory-test")
        try {
            val duplicate = directory.resolve("duplicate.axp")
            PackageArchiveWriter().pack(fixture, duplicate)
            duplicateCentralEntry(duplicate)
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(duplicate) }

            val unknown = directory.resolve("unknown-capability.axp")
            writeFixtureArchive(unknown) { manifest -> manifest.replace("activity.end.v1", "activity.bad.v1") }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(unknown) }

            val duplicateKey = directory.resolve("duplicate-key.axp")
            writeFixtureArchive(duplicateKey) { manifest ->
                manifest.replace(
                    "\"package_id\": \"axiora.ca.first-slice\",",
                    "\"package_id\": \"axiora.ca.first-slice\", \"package_id\": \"duplicate\",",
                )
            }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(duplicateKey) }

            val corruptHash = directory.resolve("corrupt-hash.axp")
            writeFixtureArchive(corruptHash) { manifest -> manifest.replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64)) }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(corruptHash) }

            val undeclared = directory.resolve("undeclared.axp")
            writeFixtureArchive(undeclared, mapOf("extra.json" to "{}".toByteArray())) { it }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(undeclared) }

            val invalidUtf8 = directory.resolve("invalid-utf8.axp")
            writeInvalidUtf8Payload(invalidUtf8)
            assertFailsWith<Exception> { PackageArchiveReader().inspect(invalidUtf8) }
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `inspect rejects compressed expansion without inflating it`() {
        val directory = Files.createTempDirectory("axiora-expansion-test")
        try {
            val archive = directory.resolve("compressed.axp")
            writeFixtureArchive(
                archive,
                mapOf("large.json" to ByteArray(3 * 1024 * 1024) { 'x'.code.toByte() }),
                compression = true,
            ) { it }
            assertFailsWith<IllegalArgumentException> { PackageArchiveReader().inspect(archive) }
        } finally {
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun mutateArchive(
        path: Path,
        mutation: (ByteArray) -> Unit,
    ) {
        val bytes = Files.readAllBytes(path)
        mutation(bytes)
        Files.write(path, bytes)
    }

    private fun writeFixtureArchive(
        output: Path,
        extra: Map<String, ByteArray> = emptyMap(),
        compression: Boolean = false,
        transformManifest: (String) -> String,
    ) {
        val entries = sortedMapOf<String, ByteArray>()
        Files.walk(fixture).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "README.md" }.forEach { file ->
                val name = fixture.relativize(file).toString().replace('\\', '/')
                val bytes = Files.readAllBytes(file)
                entries[name] = if (name == "manifest.json") transformManifest(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes
            }
        }
        entries.putAll(extra)
        ZipOutputStream(Files.newOutputStream(output), Charsets.UTF_8).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry =
                    ZipEntry(name).apply {
                        setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                        if (!compression) {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun writeInvalidUtf8Payload(output: Path) {
        val entries = sortedMapOf<String, ByteArray>()
        Files.walk(fixture).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "README.md" }.forEach { file ->
                entries[fixture.relativize(file).toString().replace('\\', '/')] = Files.readAllBytes(file)
            }
        }
        val payload = entries.getValue("concepts/core.json").copyOf().apply { this[0] = 0xff.toByte() }
        entries["concepts/core.json"] = payload
        val mapper = ObjectMapper()
        val manifest = mapper.readTree(entries.getValue("manifest.json"))
        manifest.path("files").forEach { record ->
            if (record.path("path").asText() == "concepts/core.json") {
                (record as com.fasterxml.jackson.databind.node.ObjectNode)
                    .put("bytes", payload.size)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) })
            }
        }
        entries["manifest.json"] = mapper.writeValueAsBytes(manifest)
        ZipOutputStream(Files.newOutputStream(output), Charsets.UTF_8).use { zip ->
            entries.forEach { (name, bytes) ->
                val crc =
                    java.util.zip
                        .CRC32()
                        .apply { update(bytes) }
                val entry =
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc.value
                        setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                    }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun duplicateCentralEntry(path: Path) {
        val bytes = Files.readAllBytes(path)
        val eocd = (bytes.size - 22 downTo 0).first { get32(bytes, it) == 0x06054b50L }
        val centralStart = get32(bytes, eocd + 16).toInt()
        val nameLength = get16(bytes, centralStart + 28)
        val recordLength = 46 + nameLength + get16(bytes, centralStart + 30) + get16(bytes, centralStart + 32)
        val newBytes = ByteArray(bytes.size + recordLength)
        bytes.copyInto(newBytes, 0, 0, eocd)
        bytes.copyInto(newBytes, eocd, centralStart, centralStart + recordLength)
        bytes.copyInto(newBytes, eocd + recordLength, eocd, bytes.size)
        val newEocd = eocd + recordLength
        put16(newBytes, newEocd + 8, get16(newBytes, newEocd + 8) + 1)
        put16(newBytes, newEocd + 10, get16(newBytes, newEocd + 10) + 1)
        put32(newBytes, newEocd + 12, get32(newBytes, newEocd + 12) + recordLength)
        Files.write(path, newBytes)
    }

    private fun centralRecord(
        bytes: ByteArray,
        name: String,
    ): Int {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = 0
        while (position + 46 <= bytes.size) {
            if (buffer.getInt(position) == 0x02014b50) {
                val nameLength = get16(bytes, position + 28)
                val entryName = bytes.copyOfRange(position + 46, position + 46 + nameLength).toString(Charsets.UTF_8)
                if (entryName == name) return position
                position += 46 + nameLength + get16(bytes, position + 30) + get16(bytes, position + 32)
            } else {
                position++
            }
        }
        error("ZIP entry not found: $name")
    }

    private fun get16(
        bytes: ByteArray,
        position: Int,
    ): Int =
        ByteBuffer
            .wrap(bytes, position, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xffff

    private fun get32(
        bytes: ByteArray,
        position: Int,
    ): Long =
        ByteBuffer
            .wrap(bytes, position, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xffffffffL

    private fun put16(
        bytes: ByteArray,
        position: Int,
        value: Int,
    ) {
        ByteBuffer.wrap(bytes, position, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }

    private fun put32(
        bytes: ByteArray,
        position: Int,
        value: Long,
    ) {
        ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
    }
}
