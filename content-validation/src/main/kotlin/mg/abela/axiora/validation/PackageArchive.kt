package mg.abela.axiora.validation

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object PackageLimits {
    const val ARCHIVE_BYTES = 20 * 1024 * 1024
    const val TOTAL_BYTES = 50 * 1024 * 1024
    const val FILES = 500
    const val PAYLOAD_BYTES = 2 * 1024 * 1024
    const val MANIFEST_BYTES = 256 * 1024
}

data class PackageRevisionDescriptor(
    val packageId: String,
    val version: String,
    val manifestSha256: String,
    val archiveSha256: String,
    val payloadCount: Int,
    val uncompressedBytes: Long,
)

data class PackageBuildResult(
    val descriptor: PackageRevisionDescriptor,
    val reportPath: Path,
)

/** Deterministic schema-0.1 archive writer. It never mutates the authoring directory. */
class PackageArchiveWriter(
    private val validator: ContentValidator = ContentValidator(),
) {
    private val mapper = ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())

    fun pack(
        source: Path,
        output: Path,
        overwrite: Boolean = false,
    ): PackageBuildResult {
        val sourceRoot = source.toAbsolutePath().normalize()
        val target = output.toAbsolutePath().normalize()
        require(Files.isDirectory(sourceRoot)) { "Input directory does not exist: $sourceRoot" }
        require(!target.startsWith(sourceRoot)) { "Output must be outside the authoring directory: $target" }
        require(!Files.exists(target) || overwrite) { "Output already exists: $target (use --overwrite)" }
        val validation = validator.validate(sourceRoot)
        require(validation.valid) { validation.diagnostics.joinToString("\n") { "${it.code} ${it.file}:${it.path}: ${it.message}" } }

        val manifestPath = sourceRoot.resolve("manifest.json")
        val sourceManifestBytes = Files.readAllBytes(manifestPath)
        val manifestNode = mapper.readTree(sourceManifestBytes) as ObjectNode
        val payloadPaths =
            manifestNode
                .path("files")
                .map { it.path("path").asText() }
                .distinct()
                .sorted()
        require(payloadPaths.size + 1 <= PackageLimits.FILES) { "Package exceeds ${PackageLimits.FILES} archive files." }
        var total = 0L
        val sourceHashes = sortedMapOf("manifest.json" to sha256(sourceManifestBytes))
        val payloads =
            payloadPaths
                .associateWith { rel ->
                    val raw = Files.readAllBytes(sourceRoot.resolve(rel))
                    sourceHashes[rel] = sha256(raw)
                    require(raw.size <= PackageLimits.PAYLOAD_BYTES) { "Payload '$rel' exceeds ${PackageLimits.PAYLOAD_BYTES} bytes." }
                    total += raw.size
                    require(total <= PackageLimits.TOTAL_BYTES) { "Package exceeds ${PackageLimits.TOTAL_BYTES} uncompressed bytes." }
                    val parsed = parseBoundedJson(raw, rel, mapper)
                    val bytes = canonicalBytes(parsed)
                    require(
                        bytes.size <= PackageLimits.PAYLOAD_BYTES,
                    ) { "Serialized payload '$rel' exceeds ${PackageLimits.PAYLOAD_BYTES} bytes." }
                    bytes
                }
        val fileRecords = mapper.createArrayNode()
        payloads.forEach { (path, bytes) ->
            fileRecords.add(
                mapper
                    .createObjectNode()
                    .put("path", path)
                    .put("bytes", bytes.size)
                    .put("sha256", sha256(bytes)),
            )
        }
        manifestNode.set<ArrayNode>("files", fileRecords)
        val manifestBytes = canonicalBytes(manifestNode)
        require(manifestBytes.size <= PackageLimits.MANIFEST_BYTES) { "Manifest exceeds ${PackageLimits.MANIFEST_BYTES} bytes." }
        val entries = (payloads + mapOf("manifest.json" to manifestBytes)).toSortedMap()
        require(entries.size <= PackageLimits.FILES) { "Package exceeds ${PackageLimits.FILES} archive files." }
        total += manifestBytes.size
        require(total <= PackageLimits.TOTAL_BYTES) { "Package exceeds ${PackageLimits.TOTAL_BYTES} uncompressed bytes." }

        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temp), Charsets.UTF_8).use { zip ->
                entries.forEach { (name, bytes) ->
                    val crc = CRC32().apply { update(bytes) }
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
            require(Files.size(temp) <= PackageLimits.ARCHIVE_BYTES) { "Archive exceeds ${PackageLimits.ARCHIVE_BYTES} bytes." }
            if (overwrite) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } else {
                Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        val tree = mapper.readTree(manifestBytes)
        val pkg = tree.path("package_id").asText()
        val version = tree.path("version").asText()
        val archiveHash = sha256(Files.readAllBytes(target))
        val reportPath = target.resolveSibling(target.fileName.toString() + ".report.json")
        val report =
            mapper
                .createObjectNode()
                .put("tool", "axiora-compiler-0.1.0")
                .put("policy", "package-0.1")
                .put("package_id", pkg)
                .put("version", version)
                .put("manifest_sha256", sha256(manifestBytes))
                .put("archive_sha256", archiveHash)
                .put("payload_count", payloads.size)
                .put("uncompressed_bytes", total)
        val sourceHashNode = mapper.createObjectNode()
        sourceHashes.forEach { (path, hash) -> sourceHashNode.put(path, hash) }
        report.set<ObjectNode>("source_sha256", sourceHashNode)
        val diagnosticNodes = mapper.createArrayNode()
        validation.diagnostics
            .sortedWith(compareBy({ it.code }, { it.file }, { it.path }, { it.message }))
            .forEach { diagnostic ->
                diagnosticNodes.add(
                    mapper
                        .createObjectNode()
                        .put("code", diagnostic.code)
                        .put("severity", diagnostic.severity)
                        .put("file", diagnostic.file)
                        .put("path", diagnostic.path)
                        .put("message", diagnostic.message),
                )
            }
        report.set<ArrayNode>("diagnostics", diagnosticNodes)
        Files.write(reportPath, canonicalBytes(report))
        return PackageBuildResult(
            PackageRevisionDescriptor(pkg, version, sha256(manifestBytes), archiveHash, payloads.size, total),
            reportPath,
        )
    }

    private fun canonicalBytes(node: JsonNode): ByteArray = mapper.writeValueAsBytes(sortObjectKeys(node.deepCopy<JsonNode>()))

    private fun sortObjectKeys(node: JsonNode): JsonNode =
        when {
            node.isObject -> {
                val out = mapper.createObjectNode()
                node
                    .fieldNames()
                    .asSequence()
                    .toSortedSet()
                    .forEach { key -> out.set<JsonNode>(key, sortObjectKeys(node.get(key))) }
                out
            }

            node.isArray -> {
                (node as ArrayNode).also { array ->
                    for (i in 0 until array.size()) array.set(i, sortObjectKeys(array[i]))
                }
            }

            else -> {
                node
            }
        }
}

/** Bounded archive inspector. It stages only after central-directory checks and streaming limits pass. */
class PackageArchiveReader(
    private val validator: ContentValidator = ContentValidator(),
) {
    fun inspect(archive: Path): PackageRevisionDescriptor = load(archive) { descriptor, _, _ -> descriptor }

    /** Staging exists only while the callback runs, after all archive validation succeeds. */
    fun <T> load(
        archive: Path,
        consume: (PackageRevisionDescriptor, Path, ValidationResult) -> T,
    ): T {
        require(Files.isRegularFile(archive) && !Files.isSymbolicLink(archive)) { "Archive is not a regular file." }
        val archiveSize = Files.size(archive)
        require(archiveSize <= PackageLimits.ARCHIVE_BYTES) { "Archive exceeds ${PackageLimits.ARCHIVE_BYTES} bytes." }
        val entries = centralDirectory(archive)
        require(entries.size in 1..PackageLimits.FILES) { "Archive entry count is outside supported limits." }
        val names = entries.map { it.name }
        names.forEach(::validatePath)
        require(names.distinct().size == names.size) { "Archive contains duplicate entry names." }
        require(names.map { it.lowercase() }.distinct().size == names.size) { "Archive contains case-insensitive path collisions." }
        val folded = names.map { it.lowercase() }.toSet()
        names.forEach { name ->
            var parent = name.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent.lowercase() !in folded) { "Archive file and directory paths collide: $parent and $name" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        require("manifest.json" in names) { "Archive has no root manifest.json." }
        require(names.none { it.endsWith('/') }) { "Directory entries are not allowed." }

        val staging = Files.createTempDirectory("axiora-inspect-")
        try {
            var total = 0L
            ZipFile(archive.toFile(), Charsets.UTF_8).use { zip ->
                val zipEntries = zip.entries().asSequence().toList()
                require(zipEntries.map { it.name }.toSet() == names.toSet()) { "ZIP central directory does not match entries." }
                for (entry in zipEntries) {
                    validatePath(entry.name)
                    val meta = entries.single { it.name == entry.name }
                    require(
                        meta.method == ZipEntry.STORED && !entry.isDirectory,
                    ) { "Only stored regular-file entries are supported: ${entry.name}" }
                    val limit = if (entry.name == "manifest.json") PackageLimits.MANIFEST_BYTES else PackageLimits.PAYLOAD_BYTES
                    require(
                        meta.uncompressedSize <= limit && meta.compressedSize <= PackageLimits.ARCHIVE_BYTES,
                    ) { "Entry exceeds size limit: ${entry.name}" }
                    require(
                        meta.uncompressedSize >= 0 && meta.compressedSize == meta.uncompressedSize,
                    ) { "Invalid stored entry sizes: ${entry.name}" }
                    val destination = staging.resolve(entry.name).normalize()
                    require(destination.startsWith(staging)) { "Unsafe archive path: ${entry.name}" }
                    Files.createDirectories(destination.parent)
                    val count =
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(destination).use { output ->
                                val buffer = ByteArray(8192)
                                var count = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read
                                    total += read
                                    require(count <= limit && total <= PackageLimits.TOTAL_BYTES) { "Expanded archive exceeds size limit." }
                                    output.write(buffer, 0, read)
                                }
                                count
                            }
                        }
                    require(count == meta.uncompressedSize) { "Entry size mismatch: ${entry.name}" }
                }
            }
            val result = validator.validate(staging)
            require(result.valid) { result.diagnostics.joinToString("\n") { "${it.code} ${it.file}:${it.path}: ${it.message}" } }
            val manifest = Files.readAllBytes(staging.resolve("manifest.json"))
            val mapper = ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            val tree = mapper.readTree(manifest)
            val declaredPayloads = tree.path("files").map { it.path("path").asText() }.toSet()
            require(
                declaredPayloads + "manifest.json" == names.toSet(),
            ) { "Archive entries do not match the manifest payload inventory." }
            declaredPayloads.forEach { path ->
                parseBoundedJson(Files.readAllBytes(staging.resolve(path)), path, mapper)
            }
            val descriptor =
                PackageRevisionDescriptor(
                    tree.path("package_id").asText(),
                    tree.path("version").asText(),
                    sha256(manifest),
                    sha256(Files.readAllBytes(archive)),
                    names.size - 1,
                    total,
                )
            return consume(descriptor, staging, result)
        } finally {
            Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun validatePath(name: String) {
        require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && '\u0000' !in name) { "Unsafe archive path." }
        require(name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe archive path: $name" }
    }

    private data class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Long,
    )

    private fun centralDirectory(path: Path): List<CentralEntry> {
        val bytes = Files.readAllBytes(path) // archive size was bounded before this allocation
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val lower = maxOf(0, bytes.size - 65_557)
        var eocd = -1
        for (i in bytes.size - 22 downTo lower) {
            if (b.getInt(i) == 0x06054b50) {
                eocd = i
                break
            }
        }
        require(eocd >= 0) { "ZIP end record is missing." }
        val disk = u16(b, eocd + 4)
        val centralDisk = u16(b, eocd + 6)
        val diskCount = u16(b, eocd + 8)
        val count = u16(b, eocd + 10)
        val size = u32(b, eocd + 12)
        val offset = u32(b, eocd + 16)
        val comment = u16(b, eocd + 20)
        require(disk == 0 && centralDisk == 0 && diskCount == count && count != 0xffff && size != 0xffffffffL && offset != 0xffffffffL) {
            "Multi-disk and ZIP64 archives are unsupported."
        }
        require(eocd + 22 + comment == bytes.size && offset + size <= eocd.toLong()) { "Invalid ZIP central directory bounds." }
        var pos = offset.toInt()
        val result = mutableListOf<CentralEntry>()
        repeat(count) {
            require(pos + 46 <= bytes.size && b.getInt(pos) == 0x02014b50) { "Invalid ZIP central directory entry." }
            val madeBy = u16(b, pos + 4)
            val flags = u16(b, pos + 8)
            val method = u16(b, pos + 10)
            val crc = u32(b, pos + 16)
            val compressed = u32(b, pos + 20)
            val uncompressed = u32(b, pos + 24)
            val nameLen = u16(b, pos + 28)
            val extraLen = u16(b, pos + 30)
            val commentLen = u16(b, pos + 32)
            val diskStart = u16(b, pos + 34)
            val external = u32(b, pos + 38)
            val localOffset = u32(b, pos + 42)
            require(diskStart == 0 && flags and 1 == 0) { "Encrypted or multi-disk ZIP entries are unsupported." }
            require(flags and (1 shl 11) != 0) { "ZIP entry names must be explicitly UTF-8." }
            require(compressed != 0xffffffffL && uncompressed != 0xffffffffL) { "ZIP64 entry sizes are unsupported." }
            require(pos + 46 + nameLen + extraLen + commentLen <= bytes.size) { "Truncated ZIP central directory entry." }
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT,
                    ).onUnmappableCharacter(CodingErrorAction.REPORT)
            val name = decoder.decode(ByteBuffer.wrap(bytes, pos + 46, nameLen)).toString()
            if (madeBy ushr 8 == 3) {
                val mode = (external ushr 16).toInt()
                val kind = mode and 0xf000
                require(kind == 0x8000) { "Archive entry is not a regular file: $name" }
            }
            result += CentralEntry(name, flags, method, crc, compressed, uncompressed, localOffset)
            pos += 46 + nameLen + extraLen + commentLen
        }
        require(pos.toLong() == offset + size) { "Unexpected data in ZIP central directory." }
        result.forEach { entry ->
            val local = entry.localOffset.toInt()
            require(entry.localOffset < offset && local >= 0 && local + 30 <= bytes.size && b.getInt(local) == 0x04034b50) {
                "Invalid ZIP local file header: ${entry.name}"
            }
            val flags = u16(b, local + 6)
            val method = u16(b, local + 8)
            val crc = u32(b, local + 14)
            val compressed = u32(b, local + 18)
            val uncompressed = u32(b, local + 22)
            val nameLen = u16(b, local + 26)
            val extraLen = u16(b, local + 28)
            val dataStart = local.toLong() + 30 + nameLen + extraLen
            require(flags == entry.flags && method == entry.method && nameLen > 0 && dataStart + entry.compressedSize <= offset) {
                "ZIP local header does not match central directory: ${entry.name}"
            }
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT,
                    ).onUnmappableCharacter(CodingErrorAction.REPORT)
            val localName = decoder.decode(ByteBuffer.wrap(bytes, local + 30, nameLen)).toString()
            require(localName == entry.name) { "ZIP entry names differ between local and central directories." }
            if (flags and 8 == 0) {
                require(crc == entry.crc && compressed == entry.compressedSize && uncompressed == entry.uncompressedSize) {
                    "ZIP local sizes or CRC do not match central directory: ${entry.name}"
                }
            }
        }
        return result
    }

    private fun u16(
        b: ByteBuffer,
        at: Int,
    ) = b.getShort(at).toInt() and 0xffff

    private fun u32(
        b: ByteBuffer,
        at: Int,
    ) = b.getInt(at).toLong() and 0xffffffffL
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun parseBoundedJson(
    bytes: ByteArray,
    path: String,
    mapper: ObjectMapper,
): JsonNode {
    val text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    var depth = 0
    var inString = false
    var escaped = false
    for (char in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
        } else {
            when (char) {
                '"' -> {
                    inString = true
                }

                '{', '[' -> {
                    depth++
                    require(depth <= 32) { "JSON nesting exceeds depth 32: $path" }
                }

                '}', ']' -> {
                    depth--
                }
            }
        }
    }
    require(depth == 0 && !inString) { "JSON structure is incomplete: $path" }
    return mapper.readTree(text) ?: throw IllegalArgumentException("JSON payload is empty: $path")
}
