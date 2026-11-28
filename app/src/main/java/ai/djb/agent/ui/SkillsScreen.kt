package ai.djb.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SkillsScreen(
    vm: AgentViewModel,
    state: UiState,
    onChanged: () -> Unit,
) {
    var version by remember { mutableIntStateOf(0) }
    val skills = remember(version, state.skillsCount) { vm.skills() }
    val memory = remember(version, state.memoryCount) { vm.memory() }
    val rules = remember(version, state.rulesCount) { vm.rules() }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("ما تعلّمه المساعد بنفسه", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "كل ما هنا كتبه الوكيل ويستطيع تعديله. احذف أي عنصر لتقييده.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text("المهارات (${skills.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        if (skills.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        "لا مهارات بعد. اطلب من المساعد مهمة، ثم قل له: «احفظها كمهارة».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            items(skills) { skill ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, fontWeight = FontWeight.SemiBold)
                            Text(skill.description, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.deleteSkill(skill.name); version++ }) {
                            Icon(Icons.Filled.Delete, contentDescription = "حذف")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (skill.runs == 0) 0f else skill.reliability },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    MonoLabel(
                        "خطوات=${skill.steps.size} · تشغيل=${skill.runs} · " +
                            "موثوقية=" + if (skill.runs == 0) "—" else "${(skill.reliability * 100).toInt()}%" +
                            " · ${fmt.format(Date(skill.createdAt))}"
                    )
                    val conditional = skill.steps.count { it.branches.isNotEmpty() }
                    val retrying = skill.steps.count { it.retry > 0 }
                    if (conditional > 0 || retrying > 0) {
                        Spacer(Modifier.height(4.dp))
                        MonoLabel(
                            buildString {
                                if (conditional > 0) append("شروط=$conditional")
                                if (retrying > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append("إعادة محاولة=$retrying")
                                }
                            }
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(10.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الذاكرة (${memory.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (memory.isNotEmpty()) {
                    TextButton(onClick = { vm.clearMemory(); version++ }) { Text("تفريغ") }
                }
            }
        }
        if (memory.isEmpty()) {
            item { MonoLabel("لا حقائق محفوظة.") }
        } else {
            items(memory.entries.toList()) { entry ->
                val (k, v) = entry
                SectionCard { MonoLabel("$k = $v") }
            }
        }

        item { Spacer(Modifier.height(10.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("القواعد (${rules.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (rules.isNotEmpty()) {
                    TextButton(onClick = { vm.clearRules(); version++ }) { Text("حذف الكل") }
                }
            }
        }
        if (rules.isEmpty()) {
            item { MonoLabel("لا قواعد. يمكنك إضافتها بمحادثة المساعد: «لا تتصل بأحد دون إذني».") }
        } else {
            items(rules) { rule ->
                SectionCard { Text("• $rule", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
