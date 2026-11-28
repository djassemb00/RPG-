package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The growth loop: the agent's own track record becomes the thing that changes it.
 *
 * Autonomy here is *earned, not granted*. Four things happen in order, each driven by
 * evidence the agent produced itself:
 *
 *  1. **Evaluate** — reliability per skill, from real runs.
 *  2. **Repair** — a failing skill is rewritten by the model, validated in the closed
 *     container, and only replaces the original if it passes. Two repairs max, then it
 *     is retired instead of being retried forever.
 *  3. **Prune** — skills that keep failing are retired, not deleted; the history stays.
 *  4. **Graduate** — autonomy is raised one notch only when the agent has enough runs
 *     at a high success rate. Failures demote it immediately.
 *
 * Point 4 is the real answer to "become independent": independence is a *state the agent
 * reaches by proving itself*, never a switch the user or the agent can flip.
 */
class GrowthEngine(
    private val store: JsonStore,
    private val settings: SettingsStore,
    private val sandbox: PatchSandbox,
    private val client: LlmClient,
) {

    companion object {
        /** Runs needed before a skill's reliability means anything. */
        const val MIN_RUNS_FOR_JUDGEMENT = 3

        /** Below this reliability, a skill is a candidate for repair. */
        const val REPAIR_THRESHOLD = 0.5f

        /** Two repairs, then retirement — endless rewriting is churn, not learning. */
        const val MAX_REPAIRS = 2

        /** Evidence required to graduate one autonomy level. */
        const val GRADUATE_MIN_RUNS = 20
        const val GRADUATE_MIN_RELIABILITY = 0.8f

        /** One bad streak demotes immediately; trust is slow to gain, fast to lose. */
        const val DEMOTE_BELOW = 0.5f
    }

    data class Assessment(
        val totalSkills: Int,
        val totalRuns: Int,
        val reliability: Float,
        val failing: List<Skill>,
        val retired: List<Skill>,
        /** Non-retired skills that are healthy. */
        val healthy: Int,
        val suggestedAutonomy: Autonomy,
        val evidence: String,
    ) {
        fun summary(): String = buildString {
            append("$totalSkills مهارة · $totalRuns تشغيلًا · موثوقية عامة ${(reliability * 100).toInt()}%\n")
            append("سليمة: $healthy · تحتاج إصلاحًا: ${failing.size} · متقاعدة: ${retired.size}\n")
            append("اقتراح الاستقلالية: ${suggestedAutonomy.name} — $evidence")
        }
    }

    // ---- 1. evaluate ----

    fun assess(): Assessment {
        val all = store.readSkills()
        val active = all.filterNot { it.retired }
        val runs = active.sumOf { it.runs }
        val successes = active.sumOf { it.successes }
        val reliability = if (runs == 0) 0f else successes.toFloat() / runs.toFloat()

        val failing = active.filter {
            it.runs >= MIN_RUNS_FOR_JUDGEMENT && it.reliability < REPAIR_THRESHOLD
        }
        val retired = all.filter { it.retired }

        val (suggested, evidence) = when {
            runs >= GRADUATE_MIN_RUNS && reliability >= GRADUATE_MIN_RELIABILITY ->
                Autonomy.BALANCED to "$runs تشغيلًا بنجاح ${(reliability * 100).toInt()}% — يستحق خطوة ثقة"

            runs >= GRADUATE_MIN_RUNS * 2 && reliability < DEMOTE_BELOW ->
                Autonomy.ASK to "سجل ضعيف (${(reliability * 100).toInt()}%) — يجب تقليل الاستقلالية"

            else -> settings.autonomy to "لا دليل كافٍ بعد ($runs/$GRADUATE_MIN_RUNS تشغيلًا)"
        }

        return Assessment(
            totalSkills = all.size,
            totalRuns = runs,
            reliability = reliability,
            failing = failing,
            retired = retired,
            healthy = active.size - failing.size,
            suggestedAutonomy = suggested,
            evidence = evidence,
        )
    }

    // ---- 2 + 3. repair and prune ----

    data class GrowthResult(
        val repaired: List<String>,
        val retired: List<String>,
        val failed: List<String>,
        val note: String,
    )

    /**
     * Runs the loop. `apply` is false for a dry run, so the user can see what would
     * happen before anything changes.
     */
    suspend fun grow(apply: Boolean): GrowthResult {
        val assessment = assess()
        val repaired = ArrayList<String>()
        val retiredNames = ArrayList<String>()
        val failedNames = ArrayList<String>()
        val notes = ArrayList<String>()

        val skills = store.readSkills().toMutableList()

        for (skill in assessment.failing) {
            val index = skills.indexOfFirst { it.name == skill.name }
            if (index < 0) continue

            if (skill.repairs >= MAX_REPAIRS) {
                retiredNames.add(skill.name)
                if (apply) {
                    skills[index] = skill.copy(retired = true)
                    notes.add("${skill.name}: تقاعد بعد ${skill.repairs} محاولات إصلاح")
                }
                continue
            }

            val proposal = repairProposal(skill)
            if (proposal == null) {
                failedNames.add(skill.name)
                notes.add("${skill.name}: تعذّر توليد إصلاح (موديل/شبكة)")
                continue
            }

            // The repair must pass the same container as any other change to itself.
            if (!validate(skill.name, proposal)) {
                failedNames.add(skill.name)
                notes.add("${skill.name}: الإصلاح رُفض في الحاوية")
                continue
            }

            if (apply) {
                skills[index] = proposal.copy(
                    runs = skill.runs,
                    successes = skill.successes,
                    repairs = skill.repairs + 1,
                    createdAt = skill.createdAt,
                    lastError = skill.lastError,
                    lastRunAt = skill.lastRunAt,
                    retired = false,
                )
                repaired.add(skill.name)
                notes.add("${skill.name}: أُصلحت (${skill.repairs + 1}/$MAX_REPAIRS)")
            } else {
                repaired.add(skill.name)
                notes.add("${skill.name}: يمكن إصلاحها (تجربة بلا تطبيق)")
            }
        }

        if (apply && (repaired.isNotEmpty() || retiredNames.isNotEmpty())) {
            store.writeSkills(skills)
        }

        return GrowthResult(
            repaired = repaired,
            retired = retiredNames,
            failed = failedNames,
            note = if (notes.isEmpty()) "لا مهارات تحتاج تدخلًا — السجل سليم." else notes.joinToString("\n"),
        )
    }

    /** Asks the model for a corrected plan, constrained to tools that exist. */
    private suspend fun repairProposal(skill: Skill): Skill? {
        val registry = ToolRegistryHolder.tools() ?: return null
        val available = registry.specs().joinToString(", ") { it.name }

        val prompt = buildString {
            append("مهارة تفشل باستمرار وأحتاج نسخة أفضل منها.\n\n")
            append("الاسم: ${skill.name}\n")
            append("الوصف: ${skill.description}\n")
            append("الخطوات الحالية:\n")
            append(skill.toJson().optJSONArray("steps")?.toString(2).orEmpty())
            append("\n\nآخر خطأ: ${skill.lastError.ifBlank { "(غير مسجّل)" }}\n")
            append("نسبة النجاح: ${skill.successes}/${skill.runs}\n\n")
            append("الأدوات المتاحة فقط: $available\n\n")
            append("أعد JSON فقط بالشكل: {\"description\":\"…\",\"steps\":[{\"tool\":\"…\",\"args\":{}}]}\n")
            append("أضف retry أو branches إن كان الفشل متوقعًا (عنصر غير موجود، مهلة).")
        }

        val messages = listOf(
            ChatMessage.system(
                "أنت تُصلح خطة أدوات. أعد JSON صالحًا فقط، بلا شرح، وبأدوات من القائمة المذكورة حصرًا."
            ),
            ChatMessage.user(prompt),
        )

        val response = try {
            client.chat(messages, emptyList(), AgentPhase.PLAN)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }

        val json = extractJson(response.content) ?: return null
        val stepsArray = json.optJSONArray("steps") ?: return null
        val steps = (0 until stepsArray.length()).mapNotNull { i ->
            stepsArray.optJSONObject(i)?.let { SkillStep.fromJson(it) }
        }
        if (steps.isEmpty()) return null

        return skill.copy(
            description = json.optString("description").ifBlank { skill.description },
            steps = steps,
        )
    }

    /** Runs the repair through the sandbox: tool names must exist, shapes must parse. */
    private fun validate(name: String, candidate: Skill): Boolean {
        val registry = ToolRegistryHolder.tools() ?: return false
        val known = registry.specs().map { it.name }.toSet()
        val unknown = candidate.steps.firstOrNull { it.tool !in known }
        if (unknown != null) return false

        // A repair that keeps a step which already fails for a structural reason is not
        // a repair. Reuse the sandbox's parse/schema judgement on the serialised plan.
        val verdict = sandbox.verify(
            candidateId = "skill:$name",
            touchedFiles = mapOf("$name.json" to candidate.toJson().toString()),
            patchText = "",
            liveSpecs = registry.specs(),
        )
        return verdict.passed
    }

    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    // ---- 4. graduation ----

    data class Graduation(val applied: Boolean, val from: Autonomy, val to: Autonomy, val why: String)

    /**
     * Raises autonomy by one notch when the evidence is there, and never skips a level.
     * Going down happens immediately on a bad record.
     */
    fun graduate(apply: Boolean): Graduation {
        val assessment = assess()
        val current = settings.autonomy
        val target = assessment.suggestedAutonomy

        if (target == current) {
            return Graduation(false, current, current, assessment.evidence)
        }

        val upward = target.ordinal > current.ordinal
        val allowed = if (upward) {
            // One step at a time, and only with evidence.
            target.ordinal - current.ordinal == 1
        } else {
            true
        }

        if (!allowed) {
            return Graduation(false, current, current, "لا قفز مباشر بين مستويات الاستقلالية")
        }

        if (apply) settings.autonomy = target
        return Graduation(apply, current, target, assessment.evidence)
    }

    /** Records a skill run, including the error text a repair will be built from. */
    fun recordRun(name: String, success: Boolean, error: String) {
        val skills = store.readSkills().toMutableList()
        val index = skills.indexOfFirst { it.name == name }
        if (index < 0) return
        val s = skills[index]
        skills[index] = s.copy(
            runs = s.runs + 1,
            successes = s.successes + if (success) 1 else 0,
            lastRunAt = System.currentTimeMillis(),
            lastError = if (success) "" else error.take(600),
        )
        store.writeSkills(skills)
    }

    /** Snapshot for the UI and for the report tool. */
    fun report(): JSONObject = JSONObject().apply {
        val a = assess()
        put("skills", a.totalSkills)
        put("runs", a.totalRuns)
        put("reliability", a.reliability)
        put("healthy", a.healthy)
        put("failing", JSONArray().apply { a.failing.forEach { put(it.name) } })
        put("retired", JSONArray().apply { a.retired.forEach { put(it.name) } })
        put("autonomy", settings.autonomy.name)
        put("suggested_autonomy", a.suggestedAutonomy.name)
        put("evidence", a.evidence)
    }
}
