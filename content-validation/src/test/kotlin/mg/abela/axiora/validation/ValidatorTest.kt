package mg.abela.axiora.validation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidatorTest {
    private val fixture = Path.of("../examples/prime-and-maximal").toAbsolutePath().normalize()

    @Test fun `existing example is valid`() {
        val result = ContentValidator().validate(fixture)
        assertTrue(result.valid, result.diagnostics.joinToString("\n"))
    }

    @Test fun `duplicate object keys are rejected`() {
        val root = copyFixture()
        root.resolve("manifest.json").toFile().writeText("{\"schema_version\":\"0.1.0\",\"schema_version\":\"0.1.0\"}")
        val result = ContentValidator().validate(root)
        assertTrue(result.diagnostics.any { it.code == "AXC104" }, result.diagnostics.toString())
    }

    @Test fun `unsupported schema is rejected`() {
        val root = copyFixture()
        root
            .resolve(
                "manifest.json",
            ).toFile()
            .writeText(
                root
                    .resolve("manifest.json")
                    .toFile()
                    .readText()
                    .replace("\"0.1.0\"", "\"0.2.0\""),
            )
        val result = ContentValidator().validate(root)
        assertFalse(result.valid)
        assertTrue(result.diagnostics.any { it.code == "AXC101" })
    }

    @Test fun `broken activity reference is reported`() {
        val root = copyFixture()
        val file = root.resolve("units/prime-vs-maximal.json")
        file.toFile().writeText(file.toFile().readText().replace("\"incorrect\": \"remediation\"", "\"incorrect\": \"missing-node\""))
        val result = ContentValidator().validate(root)
        assertFalse(result.valid)
        assertTrue(result.diagnostics.any { it.code == "AXC203" && it.message.contains("missing-node") }, result.diagnostics.toString())
    }

    @Test fun `unreachable activity reports stable code and path`() {
        val root = copyFixture()
        val file = root.resolve("units/prime-vs-maximal.json")
        file.toFile().writeText(
            file.toFile().readText().replace(
                "\"finish\": {",
                "\"orphan\": {\"type\": \"end\", \"source_ids\": [\"original-exposition\"], \"summary_md\": \"Unused.\"},\n    \"finish\": {",
            ),
        )
        val diagnostic =
            ContentValidator().validate(root).diagnostics.single {
                it.code == "AXC203" && it.path == "$.activities.orphan"
            }
        assertTrue(diagnostic.message.contains("unreachable"))
    }

    @Test fun `activity cycles report graph failure`() {
        val root = copyFixture()
        val file = root.resolve("units/prime-vs-maximal.json")
        file.toFile().writeText(file.toFile().readText().replace("\"next\": \"proof\"", "\"next\": \"predict\""))
        val result = ContentValidator().validate(root)
        assertTrue(result.diagnostics.any { it.code == "AXC203" && it.message.contains("cycle") }, result.diagnostics.toString())
        assertTrue(
            result.diagnostics.any {
                it.code == "AXC203" && it.message.contains("path that does not terminate")
            },
            result.diagnostics.toString(),
        )
        assertTrue(result.diagnostics.any { it.path.startsWith("$.activities") })
    }

    @Test fun `manifest must declare capabilities used by activities`() {
        val root = copyFixture()
        val file = root.resolve("manifest.json")
        file.toFile().writeText(file.toFile().readText().replace("    \"activity.end.v1\",\n", ""))
        val result = ContentValidator().validate(root)
        assertTrue(
            result.diagnostics.any {
                it.code == "AXC102" && it.path == "$.requires_capabilities" && it.message.contains("activity.end.v1")
            },
            result.diagnostics.toString(),
        )
    }

    @Test fun `assessment evidence cannot target a proof objective`() {
        val root = copyFixture()
        val file = root.resolve("units/prime-vs-maximal.json")
        file.toFile().writeText(
            file.toFile().readText().replace("\"objective_id\": \"distinguish\"", "\"objective_id\": \"prove-implication\""),
        )
        val result = ContentValidator().validate(root)
        assertTrue(
            result.diagnostics.any {
                it.code == "AXC302" && it.path == "$.activities.predict.evidence.objective_id"
            },
            result.diagnostics.toString(),
        )
    }

    @Test fun `proof self review cannot target a recognition objective`() {
        val root = copyFixture()
        val file = root.resolve("units/prime-vs-maximal.json")
        file.toFile().writeText(
            file.toFile().readText().replace("\"objective_id\": \"prove-implication\"", "\"objective_id\": \"distinguish\""),
        )
        val result = ContentValidator().validate(root)
        assertTrue(
            result.diagnostics.any {
                it.code == "AXC302" && it.path == "$.activities.proof.objective_id"
            },
            result.diagnostics.toString(),
        )
    }

    @Test fun `prerequisite relation cycles are rejected`() {
        val root = copyFixture()
        val file = root.resolve("concepts/core.json")
        file.toFile().writeText(
            file
                .toFile()
                .readText()
                .replace(
                    "\"relations\": []",
                    "\"relations\": [{\"type\": \"prerequisite\", \"target\": \"axiora.ca.maximal-ideal\"}]",
                ).replace(
                    "\"type\": \"implies\"",
                    "\"type\": \"prerequisite\"",
                ),
        )
        val result = ContentValidator().validate(root)
        assertTrue(
            result.diagnostics.any {
                it.code == "AXC203" && it.message.contains("Prerequisite relations contain a cycle")
            },
            result.diagnostics.toString(),
        )
    }

    private fun copyFixture(): Path {
        val target = Files.createTempDirectory("axiora-fixture")
        Files.walk(fixture).use { paths ->
            paths.forEach { source ->
                val relative = fixture.relativize(source)
                val dest = target.resolve(relative)
                if (Files.isDirectory(source)) Files.createDirectories(dest) else Files.copy(source, dest)
            }
        }
        return target
    }
}
