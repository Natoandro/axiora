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
        root.resolve("manifest.json").toFile().writeText(root.resolve("manifest.json").toFile().readText().replace("\"0.1.0\"", "\"0.2.0\""))
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

    private fun copyFixture(): Path {
        val target = Files.createTempDirectory("axiora-fixture")
        Files.walk(fixture).use { paths -> paths.forEach { source ->
            val relative = fixture.relativize(source); val dest = target.resolve(relative)
            if (Files.isDirectory(source)) Files.createDirectories(dest) else Files.copy(source, dest)
        } }
        return target
    }
}
