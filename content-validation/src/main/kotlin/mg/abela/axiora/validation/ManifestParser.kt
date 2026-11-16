package mg.abela.axiora.validation

import com.fasterxml.jackson.databind.JsonNode
import mg.abela.axiora.content.ManifestFile
import mg.abela.axiora.content.ManifestUnit
import mg.abela.axiora.content.StableId

internal fun ValidationContext.parseManifestUnits(n: JsonNode?): List<ManifestUnit> {
    val f = "manifest.json"
    val p = "$.units"
    if (n == null || !n.isArray ||
        n.isEmpty
    ) {
        error("AXC110", f, p, "Expected a nonempty unit array.")
        return emptyList()
    }
    return n.mapIndexedNotNull { i, x ->
        val q = "$p[$i]"
        if (!obj(x, f, q, setOf("id", "version", "path"))) return@mapIndexedNotNull null
        val id = id(x["id"], f, "$q.id")
        val v = revision(x["version"], f, "$q.version")
        val path = string(x["path"], f, "$q.path", 240)
        if (path != null) safePath(path, f, "$q.path")
        if (id != null && v != null &&
            path != null
        ) {
            ManifestUnit(
                StableId(id),
                v,
                path,
            )
        } else {
            null
        }
    }
}

internal fun ValidationContext.parseFiles(n: JsonNode?): List<ManifestFile> {
    val f = "manifest.json"
    val p = "$.files"
    if (n == null || !n.isArray ||
        n.isEmpty
    ) {
        error("AXC110", f, p, "Expected a nonempty file array.")
        return emptyList()
    }
    return n.mapIndexedNotNull { i, x ->
        val q = "$p[$i]"
        if (!obj(x, f, q, setOf("path", "bytes", "sha256"))) return@mapIndexedNotNull null
        val path = string(x["path"], f, "$q.path", 240)
        val size = x["bytes"]?.takeIf { it.isIntegralNumber && it.longValue() >= 0 }?.longValue()
        if (size == null) error("AXC110", f, "$q.bytes", "Expected a nonnegative integer.")
        val hash = string(x["sha256"], f, "$q.sha256", 64)
        if (hash != null && !Regex("[a-f0-9]{64}").matches(hash)) error("AXC110", f, "$q.sha256", "Expected lowercase SHA-256 hex.")
        if (path != null && size != null && hash != null) {
            ManifestFile(path, size, hash)
        } else {
            null
        }
    }
}

internal fun ValidationContext.validateReview(n: JsonNode?) {
    val f = "manifest.json"
    if (!obj(n, f, "$.review", setOf("status", "note"))) return
    val status = string(n?.get("status"), f, "$.review.status", 40)
    if (status !in
        setOf("draft", "mathematically_reviewed", "pilot_reviewed")
    ) {
        error("AXC110", f, "$.review.status", "Unsupported review status.")
    }
    string(n?.get("note"), f, "$.review.note")
    if (status in
        setOf("mathematically_reviewed", "pilot_reviewed")
    ) {
        warning("AXC501", f, "$.review", "This author-declared review status is not authenticated by the validator.")
    }
}
