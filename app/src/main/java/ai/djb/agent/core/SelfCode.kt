package ai.djb.agent.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Read-only access to the app's own Kotlin sources, plus a place to stage patches.
 *
 * Why a read-only workspace with a staging area instead of live editing:
 *  - A compiled APK's code cannot be changed from inside itself. What *can* happen is
 *    the agent reading its own logic, finding a real defect, and producing an exact
 *    patch a human (or a build pipeline) applies. That is honest self-development.
 *  - Every change is a reviewable text diff in a file the user can export, so nothing
 *    happens behind their back.
 *
 * The snapshot ships as flat Kotlin files under `assets/source` (named `dir__File.kt`),
 * rebuilt on every build so it always matches the compiled code.
 */
class SelfCode(private val context: Context, private val store: JsonStore) {

    companion object {
        private const val ASSET_DIR = "source"
        private const val PATCH_DIR = "patches"
        private const val LOG_FILE = "selfcode_log.json"
        private const val MAX_READ_BYTES = 60_000
    }

    data class SourceFile(
        val name: String,
        val bytes: Int,
    ) {
        /** `core__AdbBridge.kt` becomes `core/AdbBridge.kt`. */
        val relativePath: String get() = name.replace("__", "/")
    }

    /** Files available in the read-only snapshot. */
    fun list(): List<SourceFile> {
        val assets = runCatching { context.assets.list(ASSET_DIR)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        return assets.filter { it.endsWith(".kt") }.sorted().mapNotNull { name ->
            val size = runCatching {
                context.assets.open("$ASSET_DIR/$name").use { it.readBytes().size }
            }.getOrDefault(0)
            if (size == 0) null else SourceFile(name, size)
        }
    }

    fun read(nameOrPath: String): String? {
        val flat = flatten(nameOrPath.trim())
        return runCatching {
            context.assets.open("$ASSET_DIR/$flat").use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_READ_BYTES) {
                    bytes.copyOf(MAX_READ_BYTES).decodeToString() +
                        "\n… (مقتطع: الملف ${bytes.size / 1024} كيلوبايت)"
                } else {
                    bytes.decodeToString()
                }
            }
        }.getOrNull()
    }

    /** Case-insensitive search across the snapshot; returns matches with context. */
    fun search(query: String, maxHits: Int = 40): List<Pair<String, Int>> {
        if (query.isBlank()) return emptyList()
        val needle = query.lowercase()
        val hits = ArrayList<Pair<String, Int>>()
        for (file in list()) {
            if (hits.size >= maxHits) break
            val content = read(file.name) ?: continue
            content.lineSequence().forEachIndexed { index, line ->
                if (hits.size < maxHits && line.lowercase().contains(needle)) {
                    hits.add(file.relativePath to (index + 1))
                }
            }
        }
        return hits
    }

    /** One line of a specific file, so a patch can be reasoned about precisely. */
    fun lines(name: String, from: Int, to: Int): String? {
        val content = read(name) ?: return null
        val all = content.lines()
        val start = (from - 1).coerceIn(0, all.size)
        val end = to.coerceIn(start, all.size)
        return all.subList(start, end).joinToString("\n")
    }

    // ---- patch staging ----

    data class Patch(
        val id: String,
        val file: String,
        val reason: String,
        val createdAt: Long,
        val applied: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("file", file)
            put("reason", reason)
            put("created_at", createdAt)
            put("applied", applied)
        }

        companion object {
            fun fromJson(obj: JSONObject) = Patch(
                id = obj.optString("id"),
                file = obj.optString("file"),
                reason = obj.optString("reason"),
                createdAt = obj.optLong("created_at"),
                applied = obj.optBoolean("applied"),
            )
        }
    }

    /**
     * Stages a patch as a real unified diff and refuses anything that would corrupt the
     * file: empty search text, text that doesn't occur exactly once, or identical
     * replacement. A patch that cannot be applied is worse than no patch.
     */
    fun stagePatch(nameOrPath: String, find: String, replace: String, reason: String):
        Result<Pair<Patch, String>> {

        if (find.isBlank()) return Result.failure(IllegalArgumentException("نص البحث فارغ."))
        if (find == replace) return Result.failure(IllegalArgumentException("البحث والاستبدال متطابقان."))

        val source = read(nameOrPath)
            ?: return Result.failure(IllegalArgumentException("ملف غير موجود في اللقطة: $nameOrPath"))

        val occurrences = countOccurrences(source, find)
        if (occurrences == 0) {
            return Result.failure(IllegalArgumentException("النص غير موجود في الملف — راجع المحتوى الحالي أولًا."))
        }
        if (occurrences > 1) {
            return Result.failure(
                IllegalArgumentException("النص يتكرر $occurrences مرة — اجعله أطول ليكون فريدًا.")
            )
        }

        val updated = source.replace(find, replace)
        if (!balanced(updated)) {
            return Result.failure(
                IllegalArgumentException("التعديل يجعل توازن الأقواس/الأقواس الدائرية مختلًّا — رُفض.")
            )
        }

        val flat = flatten(nameOrPath)
        val id = hash(flat + find + replace).take(12)
        val patch = Patch(id, flat.replace("__", "/"), reason, System.currentTimeMillis())
        val diff = unifiedDiff(patch.file, source, updated, find, replace)

        val dir = File(store.privateDir(), PATCH_DIR).apply { mkdirs() }
        File(dir, "$id.json").writeText(patch.toJson().put("find", find).put("replace", replace).toString(2))
        File(dir, "$id.diff").writeText(diff)
        record(patch)

        return Result.success(patch to diff)
    }

    fun listPatches(): List<Patch> {
        val arr = store.readArray(LOG_FILE)
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.let(Patch::fromJson) }
            .sortedByDescending { it.createdAt }
    }

    fun diffOf(id: String): String? =
        runCatching { File(File(store.privateDir(), PATCH_DIR), "$id.diff").readText() }.getOrNull()

    fun diffDir(): File = File(store.privateDir(), PATCH_DIR).apply { mkdirs() }

    /** Marks a patch as applied, for the case where the user applied it in the repo. */
    fun markApplied(id: String) {
        val patches = listPatches().map { if (it.id == id) it.copy(applied = true) else it }
        store.writeArray(LOG_FILE, JSONArray().apply { patches.forEach { put(it.toJson()) } })
    }

    // ---- helpers ----

    private fun record(patch: Patch) {
        val patches = listPatches().toMutableList()
        patches.removeAll { it.id == patch.id }
        patches.add(0, patch)
        store.writeArray(LOG_FILE, JSONArray().apply { patches.forEach { put(it.toJson()) } })
    }

    private fun flatten(nameOrPath: String): String {
        val cleaned = nameOrPath.trim().removePrefix("app/src/main/java/ai/djb/agent/")
            .removePrefix("ai/djb/agent/").removePrefix("./")
        return cleaned.replace("/", "__")
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    /**
     * Brace/paren balance check that ignores string literals and comments — the naive
     * count is wrong often enough (braces inside "${…}" and in KDoc) that it produced
     * false alarms during development.
     */
    private fun balanced(text: String): Boolean {
        val stripped = stripLiterals(text)
        if (stripped.count { it == '{' } != stripped.count { it == '}' }) return false
        if (stripped.count { it == '(' } != stripped.count { it == ')' }) return false
        return true
    }

    private fun stripLiterals(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inRaw = false
        var inString = false
        var inLineComment = false
        var inBlockComment = false

        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'

            when {
                inLineComment -> {
                    if (c == '\n') { inLineComment = false; out.append(c) }
                }
                inBlockComment -> {
                    if (c == '*' && next == '/') { inBlockComment = false; i++ }
                }
                inRaw -> {
                    if (c == '"' && next == '"' && i + 2 < text.length && text[i + 2] == '"') {
                        inRaw = false
                        i += 2
                    }
                }
                inString -> {
                    if (c == '\\') i++
                    else if (c == '"') inString = false
                }
                c == '/' && next == '/' -> inLineComment = true
                c == '/' && next == '*' -> { inBlockComment = true; i++ }
                c == '"' && next == '"' && i + 2 < text.length && text[i + 2] == '"' -> {
                    inRaw = true
                    i += 2
                }
                c == '"' -> inString = true
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun unifiedDiff(path: String, before: String, after: String, find: String, replace: String): String {
        val context = 3
        val beforeLines = before.lines()
        val afterLines = after.lines()
        val firstChanged = beforeLines.indexOfFirst { line ->
            find.lines().firstOrNull()?.let { line.contains(it) } == true
        }.coerceAtLeast(0)

        val fromLine = (firstChanged - context).coerceAtLeast(0)
        val toLine = (firstChanged + find.lines().size + context).coerceAtMost(beforeLines.size)

        val builder = StringBuilder()
        builder.append("--- a/$path\n")
        builder.append("+++ b/$path\n")
        builder.append("@@ -${fromLine + 1},${toLine - fromLine} +${fromLine + 1},${toLine - fromLine} @@\n")
        beforeLines.subList(fromLine, toLine).forEach { builder.append('-').append(it).append('\n') }
        afterLines.subList(
            fromLine.coerceAtMost(afterLines.size),
            toLine.coerceAtMost(afterLines.size),
        ).forEach { builder.append('+').append(it).append('\n') }

        if (!replace.isBlank() && !builder.contains(replace.lines().firstOrNull().orEmpty())) {
            builder.append("+").append(replace.lines().firstOrNull().orEmpty()).append('\n')
        }
        return builder.toString()
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
