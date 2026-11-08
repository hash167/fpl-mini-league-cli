import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JarParityTest {
    @Test
    fun `zip timestamps are not identity`() {
        val left = tempZip(mapOf("web/VersionResource.class" to byteArrayOf(1, 2, 3)), time = 1_000)
        val right = tempZip(mapOf("web/VersionResource.class" to byteArrayOf(1, 2, 3)), time = 9_000_000)
        assertTrue(left.readBytes().contentEquals(right.readBytes()).not(), "zip metadata should differ")
        val report = compareInputs(loadInput(left), loadInput(right))
        assertEquals(ParityStatus.CONTENT_MATCH, report.status)
        assertEquals(1, report.compared)
        assertTrue(report.mismatched.isEmpty())
    }

    @Test
    fun `build label substitution is not a behavior mismatch`() {
        val live = tempZip(
            mapOf(
                "fpl-build.properties" to "build=9.01\n".toByteArray(),
                "assets/index.html" to "const APP_BUILD = \"9.01\";\n".toByteArray(),
                "assets/sw.js" to (
                    "// FPL Live Tracker service worker, build 9.01.\nconst BUILD = \"9.01\";\n"
                    ).toByteArray(),
                "web/UiResource.class" to byteArrayOf(9, 9)
            ),
            time = 10
        )
        val candidate = tempZip(
            mapOf(
                "fpl-build.properties" to "build=9.02\n".toByteArray(),
                "assets/index.html" to "const APP_BUILD = \"9.02\";\n".toByteArray(),
                "assets/sw.js" to (
                    "// FPL Live Tracker service worker, build 9.02.\nconst BUILD = \"9.02\";\n"
                    ).toByteArray(),
                "web/UiResource.class" to byteArrayOf(9, 9)
            ),
            time = 20
        )
        val report = compareInputs(loadInput(live), loadInput(candidate))
        assertEquals(ParityStatus.CONTENT_MATCH, report.status)
        assertEquals(3, report.buildLabelOnly.size)
        assertEquals(0, exitCode(report))
        assertTrue(formatReport(report).contains("build-label-only: 3"))
    }

    @Test
    fun `a frontend byte change is a mismatch`() {
        val live = tempZip(mapOf("assets/index.html" to "<main>live</main>\n".toByteArray()), time = 1)
        val candidate = tempZip(mapOf("assets/index.html" to "<main>other</main>\n".toByteArray()), time = 1)
        val report = compareInputs(loadInput(live), loadInput(candidate))
        assertEquals(ParityStatus.MISMATCH, report.status)
        assertEquals(1, report.mismatched.size)
        assertEquals(1, exitCode(report))
    }

    @Test
    fun `one matching class is not parity`() {
        val dir = createTempDirectory("partial-extract").toFile()
        try {
            File(dir, "LiveEngineKt.class").writeBytes(byteArrayOf(7, 7, 7))
            val candidate = tempZip(mapOf("LiveEngineKt.class" to byteArrayOf(7, 7, 7), "FplService.class" to byteArrayOf(1)), time = 1)
            val report = compareInputs(loadInput(dir), loadInput(candidate))
            assertEquals(ParityStatus.INCOMPLETE, report.status)
            assertEquals(2, exitCode(report))
            assertTrue(formatReport(report).contains("not parity"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a class the candidate lacks is a mismatch even in a partial extract`() {
        val dir = createTempDirectory("live-only").toFile()
        try {
            File(dir, "OnlyLive.class").writeBytes(byteArrayOf(4))
            val candidate = tempZip(mapOf("Other.class" to byteArrayOf(4)), time = 1)
            val report = compareInputs(loadInput(dir), loadInput(candidate))
            assertEquals(ParityStatus.MISMATCH, report.status)
            assertEquals(listOf("OnlyLive.class"), report.onlyLive)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `manifest vendor line is packaging only`() {
        val live = manifest("Created-By: 17.0.20.1 (Eclipse Adoptium)\r\n")
        val candidate = manifest("Created-By: 17.0.20.1 (Ubuntu)\r\n")
        val report = compareInputs(loadInput(live), loadInput(candidate))
        assertEquals(ParityStatus.CONTENT_MATCH, report.status)
        assertEquals(1, report.packagingOnly.size)
        assertTrue(report.mismatched.isEmpty())
    }

    @Test
    fun `a different main class is a mismatch`() {
        val live = manifest("Main-Class: web.FplWebApplication\r\n")
        val candidate = manifest("Main-Class: MainKt\r\n")
        val report = compareInputs(loadInput(live), loadInput(candidate))
        assertEquals(ParityStatus.MISMATCH, report.status)
    }

    @Test
    fun `partial captures are refused`() {
        val file = File(createTempDirectory("capture").toFile(), "app.jar.partial")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val error = assertFailsWith<RejectedInput> { loadInput(file) }
        assertTrue(error.message!!.contains("partial"))
        val nested = createTempDirectory("nested-partial").toFile()
        try {
            File(nested, "foo.partial.class").writeBytes(byteArrayOf(1))
            assertFailsWith<RejectedInput> { loadInput(nested) }
        } finally {
            nested.deleteRecursively()
        }
    }

    @Test
    fun `expect sha256 rejects a different file`() {
        val jar = tempZip(mapOf("A.class" to byteArrayOf(1)), time = 1)
        assertFailsWith<RejectedInput> { loadInput(jar, expectSha256 = "ab") }
    }

    @Test
    fun `report and sidecar do not echo entry bytes`() {
        val secret = "super-secret-token-value"
        val jar = tempZip(mapOf("Secret.class" to secret.toByteArray()), time = 1)
        val report = compareInputs(loadInput(jar), loadInput(jar))
        assertEquals(ParityStatus.CONTENT_MATCH, report.status)
        assertFalse(formatReport(report).contains(secret))
        val out = File(jar.parentFile, "meta.json")
        val code = runJarParity(arrayOf(
            "digest",
            "--jar", jar.absolutePath,
            "--out", out.absolutePath,
            "--git-commit", "abc",
            "--git-dirty", "false",
            "--app-build", "9.01"
        ))
        assertEquals(0, code)
        val json = out.readText()
        assertTrue(json.contains("\"embedGitRevision\": false"))
        assertTrue(json.contains("does not identify the running image"))
        assertFalse(json.contains(secret))
        assertFalse(json.contains("liveProvenance"))
    }

    @Test
    fun `application resources do not embed a git revision`() {
        assertNull(JarParityTest::class.java.getResource("/fpl-revision.properties"))
    }

    private fun manifest(createdOrMain: String): File {
        val body = "Manifest-Version: 1.0\r\n${createdOrMain}\r\n"
        return tempZip(mapOf("META-INF/MANIFEST.MF" to body.toByteArray()), time = 5)
    }

    private fun tempZip(entries: Map<String, ByteArray>, time: Long): File {
        val dir = createTempDirectory("jar-parity").toFile()
        val file = File(dir, "sample.jar")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries.toSortedMap()) {
                val entry = ZipEntry(name)
                entry.time = time
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
