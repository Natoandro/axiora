package mg.abela.axiora.content

@JvmInline value class StableId(
    val value: String,
)

data class Revision(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(value: String): Revision? =
            Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
                .matchEntire(value)
                ?.destructured
                ?.let { (a, b, c) -> Revision(a.toInt(), b.toInt(), c.toInt()) }
    }
}

data class Concept(
    val id: StableId,
    val relations: List<Relation>,
)

data class Relation(
    val type: String,
    val target: StableId,
)

data class Objective(
    val id: String,
    val conceptId: StableId,
    val skill: String,
)

data class ManifestUnit(
    val id: StableId,
    val version: Revision,
    val path: String,
)

data class ManifestFile(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

data class ContentManifest(
    val schemaVersion: Revision,
    val packageId: StableId,
    val version: Revision,
    val capabilities: Set<String>,
    val conceptFiles: List<String>,
    val units: List<ManifestUnit>,
    val entryUnits: Set<StableId>,
    val files: List<ManifestFile>,
)

sealed interface Activity {
    val sourceIds: List<String>
}

data class Exposition(
    override val sourceIds: List<String>,
    val next: String,
) : Activity

data class Choice(
    override val sourceIds: List<String>,
    val options: Set<String>,
    val expected: String,
    val correct: String,
    val incorrect: String,
    val evidence: Pair<String, String>?,
) : Activity

data class Proof(
    override val sourceIds: List<String>,
    val objectiveId: String,
    val next: String,
    val hintIds: Set<String> = emptySet(),
    val rubricIds: Set<String> = emptySet(),
) : Activity

data class Reflection(
    override val sourceIds: List<String>,
    val next: String,
) : Activity

data class End(
    override val sourceIds: List<String>,
) : Activity

data class LearningUnit(
    val id: StableId,
    val version: Revision,
    val conceptIds: Set<StableId>,
    val objectives: List<Objective>,
    val sources: Set<String>,
    val entry: String,
    val activities: Map<String, Activity>,
)
