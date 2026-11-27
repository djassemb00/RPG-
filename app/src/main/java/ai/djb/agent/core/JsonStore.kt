package ai.djb.agent.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Marker type so Tools stay decoupled from the concrete shell implementation. */
interface ShellBridgeRef {
    suspend fun execute(command: String, timeoutMs: Long = 20_000): ShellBridge.Result
    fun capability(): ShellBridge.Capability
}

/** Durable, inspectable, plain-JSON state. Nothing is hidden from the user. */
class JsonStore(private val dir: File) {

    private fun file(name: String) = File(dir, name)

    @Synchronized
    fun readObject(name: String): JSONObject {
        val f = file(name)
        if (!f.exists()) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    }

    @Synchronized
    fun writeObject(name: String, obj: JSONObject) {
        file(name).writeText(obj.toString(2))
    }

    @Synchronized
    fun readArray(name: String): JSONArray {
        val f = file(name)
        if (!f.exists()) return JSONArray()
        return runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() }
    }

    @Synchronized
    fun writeArray(name: String, arr: JSONArray) {
        file(name).writeText(arr.toString(2))
    }

    @Synchronized
    fun appendLog(line: String) {
        file("log.jsonl").appendText(line.replace("\n", " ") + "\n")
    }

    @Synchronized
    fun readLog(limit: Int = 200): List<String> {
        val f = file("log.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(limit)
    }

    @Synchronized
    fun clearLog() {
        file("log.jsonl").writeText("")
    }

    fun skillsFile() = "skills.json"
    fun memoryFile() = "memory.json"
    fun rulesFile() = "rules.json"

    fun readSkills(): List<Skill> {
        val arr = readArray(skillsFile())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Skill::fromJson) }
    }

    fun writeSkills(skills: List<Skill>) {
        writeArray(skillsFile(), JSONArray().apply { skills.forEach { put(it.toJson()) } })
    }

    fun readRules(): List<String> {
        val arr = readArray(rulesFile())
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    fun writeRules(rules: List<String>) {
        writeArray(rulesFile(), JSONArray().apply { rules.forEach { put(it) } })
    }

    fun readMemory(): MutableMap<String, String> {
        val obj = readObject(memoryFile())
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optString(k)
        }
        return out
    }

    fun writeMemory(memory: Map<String, String>) {
        writeObject(memoryFile(), JSONObject().apply { memory.forEach { (k, v) -> put(k, v) } })
    }
}
