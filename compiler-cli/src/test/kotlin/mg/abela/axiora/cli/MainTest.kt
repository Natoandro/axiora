package mg.abela.axiora.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTest {
    private val fixture = Path.of("../examples/prime-and-maximal").toAbsolutePath().normalize()

    @Test fun `valid fixture text output exits zero`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf("validate", fixture.toString()), PrintStream(out), PrintStream(err))
        assertEquals(0, code)
        assertTrue(out.toString().contains("Valid content:"))
        assertEquals("", err.toString())
    }

    @Test fun `invalid content has text diagnostic and exit one`() {
        val root = copyFixture()
        val unit = root.resolve("units/prime-vs-maximal.json")
        unit.toFile().writeText(unit.toFile().readText().replace("\"incorrect\": \"remediation\"", "\"incorrect\": \"missing-node\""))
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf("validate", root.toString()), PrintStream(out), PrintStream(err))
        assertEquals(1, code)
        assertTrue(out.toString().contains("ERROR AXC203"))
        assertTrue(out.toString().contains("missing-node"))
        assertEquals("", err.toString())
    }

    @Test fun `invalid content JSON output is machine readable with exit one`() {
        val root = copyFixture()
        val manifest = root.resolve("manifest.json")
        manifest.toFile().writeText(manifest.toFile().readText().replace("\"0.1.0\"", "\"0.2.0\""))
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf("validate", root.toString(), "--format", "json"), PrintStream(out), PrintStream(err))
        assertEquals(1, code)
        assertTrue(out.toString().contains("\"valid\":false"))
        assertTrue(out.toString().contains("\"code\":\"AXC101\""))
        assertFalse(out.toString().contains("Exception"))
        assertEquals("", err.toString())
    }

    @Test fun `usage errors exit two`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf("validate", "--format", "xml", fixture.toString()), PrintStream(out), PrintStream(err))
        assertEquals(2, code)
        assertTrue(err.toString().contains("Usage: axiora validate"))
        assertEquals("", out.toString())
    }

    private fun copyFixture(): Path {
        val target = Files.createTempDirectory("axiora-cli-fixture")
        Files.walk(fixture).use { paths ->
            paths.forEach { source ->
                val relative = fixture.relativize(source)
                val destination = target.resolve(relative)
                if (Files.isDirectory(source)) Files.createDirectories(destination) else Files.copy(source, destination)
            }
        }
        return target
    }
}
