import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Compare uncompressed jar/zip entry bytes, or a directory extract, without treating ZIP
 * timestamps as identity.
 *
 * A [ParityStatus.CONTENT_MATCH] means the two complete inputs are equal after the build-label
 * and packaging normalizers. It is not image provenance and it does not authorize a deploy.
 * An incomplete extract that happens to agree on one class stays [ParityStatus.INCOMPLETE].
 *
 * Refuses file names containing `.partial`. Git commit metadata belongs in the sidecar written
 * by `writeBuildMeta`, not in the application jar: embedding it would change the bytes and
 * requires a new image.
 */
const val INVENTORY_MARKER = "fpl-jar-inventory-v1.txt"
const val INVENTORY_HEADER = "# fpl-jar-inventory v1"

enum class ParityStatus {
    CONTENT_MATCH,
    MISMATCH,
    INCOMPLETE
}

data class LoadedInput(
    val entries: Map<String, ByteArray>,
    val complete: Boolean,
    val notes: List<String>,
    val fileSha256: String?
)

data class ParityReport(
    val status: ParityStatus,
    val compared: Int,
    val buildLabelOnly: List<String>,
    val packagingOnly: List<String>,
    val mismatched: List<String>,
    val onlyLive: List<String>,
    val onlyCandidate: List<String>,
    val notes: List<String>
)

class RejectedInput(message: String) : RuntimeException(message)

fun sha256Bytes(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1024 * 64)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun rejectPartialName(name: String) {
    if (name.contains(".partial", ignoreCase = true)) {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        throw RejectedInput("refusing partial capture '$base'")
    }
}

fun loadInput(path: File, expectSha256: String? = null): LoadedInput {
    rejectPartialName(path.name)
    if (!path.exists()) throw RejectedInput("input does not exist: ${path.name}")
    if (path.isDirectory) {
        if (expectSha256 != null) {
            throw RejectedInput("expect-sha256 applies to a jar file, not a directory extract")
        }
        return loadDirectory(path)
    }
    if (expectSha256 != null) {
        val got = sha256File(path)
        if (!got.equals(expectSha256.trim(), ignoreCase = true)) {
            throw RejectedInput("sha256 mismatch for ${path.name}")
        }
    }
    return loadZip(path)
}

fun compareInputs(live: LoadedInput, candidate: LoadedInput): ParityReport {
    val liveNames = live.entries.keys
    val candidateNames = candidate.entries.keys
    val onlyLive = (liveNames - candidateNames).sorted()
    val onlyCandidate = (candidateNames - liveNames).sorted()
    val buildLabelOnly = mutableListOf<String>()
    val packagingOnly = mutableListOf<String>()
    val mismatched = mutableListOf<String>()
    val intersection = liveNames.intersect(candidateNames).sorted()
    for (name in intersection) {
        val liveRaw = live.entries.getValue(name)
        val candidateRaw = candidate.entries.getValue(name)
        val liveNorm = normalizeEntry(name, liveRaw)
        val candidateNorm = normalizeEntry(name, candidateRaw)
        if (liveNorm.bytes.contentEquals(candidateNorm.bytes)) {
            if (!liveRaw.contentEquals(candidateRaw)) {
                val detail = entryDetail(name, liveNorm.label, candidateNorm.label)
                if (liveNorm.kind == NormKind.BUILD_LABEL || candidateNorm.kind == NormKind.BUILD_LABEL) {
                    buildLabelOnly += detail
                } else {
                    packagingOnly += detail
                }
            }
        } else {
            mismatched += "$name live=${sha256Bytes(liveNorm.bytes).take(12)} candidate=${sha256Bytes(candidateNorm.bytes).take(12)}"
        }
    }
    val notes = mutableListOf<String>()
    notes += live.notes
    notes += candidate.notes
    notes += "ZIP timestamps, entry order, and compression methods are not compared"
    notes += "Content equality is not image provenance and does not authorize a deploy"
    val structurallyDifferent = mismatched.isNotEmpty() || (live.complete && candidate.complete && (onlyLive.isNotEmpty() || onlyCandidate.isNotEmpty()))
    val liveOnlyIsEvidence = onlyLive.isNotEmpty()
    val status = when {
        structurallyDifferent || liveOnlyIsEvidence -> ParityStatus.MISMATCH
        live.complete && candidate.complete -> ParityStatus.CONTENT_MATCH
        else -> ParityStatus.INCOMPLETE
    }
    if (status == ParityStatus.INCOMPLETE) {
        notes += "Input is not a complete entry set. Agreement on the compared classes is not parity"
        if (onlyCandidate.isNotEmpty()) {
            notes += "Candidate-only entries are omitted from the result because the live input is incomplete"
        }
    }
    return ParityReport(
        status = status,
        compared = intersection.size,
        buildLabelOnly = buildLabelOnly,
        packagingOnly = packagingOnly,
        mismatched = mismatched,
        onlyLive = if (live.complete && candidate.complete) onlyLive else onlyLive,
        onlyCandidate = if (live.complete && candidate.complete) onlyCandidate else emptyList(),
        notes = notes.distinct()
    )
}

fun exitCode(report: ParityReport): Int = when (report.status) {
    ParityStatus.CONTENT_MATCH -> 0
    ParityStatus.MISMATCH -> 1
    ParityStatus.INCOMPLETE -> 2
}

fun formatReport(report: ParityReport): String {
    val lines = mutableListOf<String>()
    lines += "status: ${report.status}"
    lines += "compared: ${report.compared}"
    lines += "build-label-only: ${report.buildLabelOnly.size}"
    report.buildLabelOnly.take(40).forEach { lines += "  $it" }
    lines += "packaging-only: ${report.packagingOnly.size}"
    report.packagingOnly.take(40).forEach { lines += "  $it" }
    lines += "mismatched: ${report.mismatched.size}"
    report.mismatched.take(40).forEach { lines += "  $it" }
    if (report.mismatched.size > 40) lines += "  ... ${report.mismatched.size - 40} more"
    lines += "only-live: ${report.onlyLive.size}"
    report.onlyLive.take(40).forEach { lines += "  $it" }
    lines += "only-candidate: ${report.onlyCandidate.size}"
    report.onlyCandidate.take(40).forEach { lines += "  $it" }
    report.notes.forEach { lines += "note: $it" }
    return lines.joinToString("\n")
}

fun buildMetaJson(
    gitCommit: String,
    gitDirty: String,
    appBuild: String,
    jarSha256: String,
    entries: List<Triple<String, String, Long>>
): String {
    val body = StringBuilder()
    body.append("{\n")
    body.append("  \"kind\": \"fpl-build-meta\",\n")
    body.append("  \"disclaimer\": \"Sidecar for the jar built from this checkout. It is not packaged in the jar and does not identify the running image. Embedding the git commit in the image changes bytes and requires a new build.\",\n")
    body.append("  \"gitCommit\": ${jsonString(gitCommit)},\n")
    body.append("  \"gitDirty\": ${jsonString(gitDirty)},\n")
    body.append("  \"appBuild\": ${jsonString(appBuild)},\n")
    body.append("  \"embedGitRevision\": false,\n")
    body.append("  \"jarSha256\": ${jsonString(jarSha256)},\n")
    body.append("  \"entryCount\": ${entries.size},\n")
    body.append("  \"entries\": [\n")
    entries.forEachIndexed { i, (name, sha, size) ->
        val comma = if (i == entries.lastIndex) "" else ","
        body.append("    {\"name\": ${jsonString(name)}, \"sha256\": ${jsonString(sha)}, \"size\": $size}$comma\n")
    }
    body.append("  ]\n")
    body.append("}\n")
    return body.toString()
}

fun main(args: Array<String>) {
    try {
        kotlin.system.exitProcess(runJarParity(args))
    } catch (e: RejectedInput) {
        System.err.println("rejected: ${e.message}")
        kotlin.system.exitProcess(3)
    }
}

fun runJarParity(args: Array<String>): Int {
    if (args.isEmpty()) throw RejectedInput("usage: digest|compare")
    return when (args[0]) {
        "digest" -> runDigest(args.drop(1))
        "compare" -> runCompare(args.drop(1))
        else -> throw RejectedInput("usage: digest|compare")
    }
}

private fun runDigest(args: List<String>): Int {
    val jar = flag(args, "--jar") ?: throw RejectedInput("digest requires --jar")
    val out = flag(args, "--out") ?: throw RejectedInput("digest requires --out")
    val commit = flag(args, "--git-commit") ?: "unknown"
    val dirty = flag(args, "--git-dirty") ?: "unknown"
    val appBuild = flag(args, "--app-build") ?: "unknown"
    val file = File(jar)
    rejectPartialName(file.name)
    val loaded = loadZip(file)
    val entries = loaded.entries.map { (name, bytes) ->
        Triple(name, sha256Bytes(bytes), bytes.size.toLong())
    }.sortedBy { it.first }
    val json = buildMetaJson(commit, dirty, appBuild, sha256File(file), entries)
    val dest = File(out)
    dest.parentFile?.mkdirs()
    dest.writeText(json)
    println("wrote ${dest.name} entries=${entries.size} embedGitRevision=false")
    return 0
}

private fun runCompare(args: List<String>): Int {
    val live = flag(args, "--live") ?: throw RejectedInput("compare requires --live")
    val candidate = flag(args, "--candidate") ?: throw RejectedInput("compare requires --candidate")
    val expect = flag(args, "--expect-sha256")
    val report = compareInputs(
        loadInput(File(live), expect),
        loadInput(File(candidate), null)
    )
    println(formatReport(report))
    return exitCode(report)
}

private fun flag(args: List<String>, name: String): String? {
    val i = args.indexOf(name)
    if (i < 0 || i + 1 >= args.size) return null
    return args[i + 1]
}

private enum class NormKind { NONE, BUILD_LABEL, PACKAGING }

private data class NormEntry(val bytes: ByteArray, val label: String?, val kind: NormKind)

private fun normalizeEntry(name: String, raw: ByteArray): NormEntry {
    return when (name) {
        "fpl-build.properties" -> normalizePattern(
            raw,
            Regex("""(?m)^build=(\S*)\s*$"""),
            "build=@APP_BUILD@"
        )
        "assets/sw.js" -> normalizeServiceWorker(raw)
        "assets/index.html" -> normalizePattern(
            raw,
            Regex("""const APP_BUILD = "([^"]*)";"""),
            "const APP_BUILD = \"@APP_BUILD@\";"
        )
        "META-INF/MANIFEST.MF" -> normalizeManifest(raw)
        else -> NormEntry(raw, null, NormKind.NONE)
    }
}

private fun normalizePattern(raw: ByteArray, pattern: Regex, replacement: String): NormEntry {
    val text = raw.toString(Charsets.UTF_8)
    val label = pattern.find(text)?.groupValues?.getOrNull(1)
    if (label == null) return NormEntry(raw, null, NormKind.NONE)
    val normalized = pattern.replace(text, replacement).toByteArray(Charsets.UTF_8)
    return NormEntry(normalized, label, NormKind.BUILD_LABEL)
}

private fun normalizeServiceWorker(raw: ByteArray): NormEntry {
    var text = raw.toString(Charsets.UTF_8)
    val constPattern = Regex("""const BUILD = "([^"]*)";""")
    val commentPattern = Regex("""(?m)^// FPL Live Tracker service worker, build .*$""")
    val label = constPattern.find(text)?.groupValues?.getOrNull(1)
    if (label == null && !commentPattern.containsMatchIn(text)) return NormEntry(raw, null, NormKind.NONE)
    text = constPattern.replace(text, "const BUILD = \"@APP_BUILD@\";")
    text = commentPattern.replace(text, "// FPL Live Tracker service worker, build @APP_BUILD@.")
    return NormEntry(text.toByteArray(Charsets.UTF_8), label, NormKind.BUILD_LABEL)
}

private fun normalizeManifest(raw: ByteArray): NormEntry {
    val text = raw.toString(Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
    val kept = text.lines().filter { line ->
        val attr = line.substringBefore(':').trim()
        attr != "Built-By" && attr != "Build-Jdk" && attr != "Build-Jdk-Spec" && attr != "Created-By"
    }
    val normalized = (kept.joinToString("\n").trimEnd() + "\n").toByteArray(Charsets.UTF_8)
    return NormEntry(normalized, null, NormKind.PACKAGING)
}

private fun entryDetail(name: String, liveLabel: String?, candidateLabel: String?): String {
    val live = liveLabel?.take(40) ?: "-"
    val candidate = candidateLabel?.take(40) ?: "-"
    return "$name live=$live candidate=$candidate"
}

private fun loadZip(file: File): LoadedInput {
    rejectPartialName(file.name)
    if (!file.isFile) throw RejectedInput("not a jar file: ${file.name}")
    val entries = linkedMapOf<String, ByteArray>()
    try {
        ZipFile(file).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                rejectPartialName(entry.name)
                if (entry.name in entries) throw RejectedInput("duplicate zip entry ${entry.name}")
                entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
    } catch (e: RejectedInput) {
        throw e
    } catch (e: Exception) {
        val detail = (e.message ?: e.javaClass.simpleName).replace(file.absolutePath, file.name).take(160)
        throw RejectedInput("unreadable zip ${file.name}: $detail")
    }
    return LoadedInput(
        entries = entries,
        complete = true,
        notes = listOf("${file.name} sha256=${sha256File(file)} entries=${entries.size}"),
        fileSha256 = sha256File(file)
    )
}

private fun loadDirectory(root: File): LoadedInput {
    val marker = File(root, INVENTORY_MARKER)
    val inventory = if (marker.isFile) parseInventory(marker.readText()) else Inventory(false, null, present = false)
    val entries = linkedMapOf<String, ByteArray>()
    root.walkTopDown().filter { it.isFile }.forEach { file ->
        rejectPartialName(file.name)
        if (file.name == INVENTORY_MARKER) return@forEach
        val rel = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
        rejectPartialName(rel)
        if (rel.split('/').any { it == ".." }) throw RejectedInput("rejecting path outside the extract")
        entries[rel] = file.readBytes()
    }
    val countMismatch = inventory.expectedEntries != null && inventory.expectedEntries != entries.size
    val complete = inventory.complete && inventory.expectedEntries == entries.size
    val notes = mutableListOf<String>()
    if (!inventory.present) notes += "no $INVENTORY_MARKER marker; extract is incomplete"
    if (countMismatch) notes += "inventory entries=${inventory.expectedEntries} but extracted files=${entries.size}"
    return LoadedInput(entries, complete, notes, null)
}

private data class Inventory(val complete: Boolean, val expectedEntries: Int?, val present: Boolean = true)

private fun parseInventory(text: String): Inventory {
    val lines = text.replace("\r\n", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.firstOrNull() != INVENTORY_HEADER) {
        throw RejectedInput("inventory must start with '$INVENTORY_HEADER'")
    }
    var complete: Boolean? = null
    var entries: Int? = null
    for (line in lines.drop(1)) {
        if (line.startsWith("#")) continue
        val parts = line.split("=", limit = 2)
        if (parts.size != 2) throw RejectedInput("bad inventory line")
        when (parts[0]) {
            "complete" -> complete = when (parts[1]) {
                "true" -> true
                "false" -> false
                else -> throw RejectedInput("complete must be true or false")
            }
            "entries" -> entries = parts[1].toIntOrNull() ?: throw RejectedInput("entries must be an integer")
            else -> throw RejectedInput("unknown inventory key ${parts[0]}")
        }
    }
    if (complete == null) throw RejectedInput("inventory missing complete=")
    if (complete && entries == null) throw RejectedInput("complete=true requires entries=<count>")
    return Inventory(complete, entries, present = true)
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}
