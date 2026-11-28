package ai.djb.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Growth screen. The design goal is honesty: the trust meter must show exactly how far
 * the agent is from the next level and why, so "independence" reads as an earned score
 * rather than a mysterious switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowthScreen(
    onBack: () -> Unit,
    state: GrowthViewState,
    onRefresh: () -> Unit,
    onImprove: (Boolean) -> Unit,
    onGraduate: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("النمو والاستقلالية") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = { TextButton(onClick = onRefresh) { Text("تحديث") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("مستوى الاستقلالية", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.autonomy, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.weight(1f))
                            MonoLabel(if (state.canGraduate) "مؤهّل للترقية" else "قيد التعلّم")
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(state.evidence)
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("سجل الأداء", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "مهارات: ${state.skills} · تشغيلات: ${state.runs}\n" +
                                "موثوقية عامة: ${(state.reliability * 100).toInt()}%\n" +
                                "سليمة: ${state.healthy} · تحتاج إصلاحًا: ${state.failing.size} · " +
                                "متقاعدة: ${state.retired.size}"
                        )
                        if (state.failing.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            MonoLabel("تحتاج إصلاحًا:\n" + state.failing.joinToString("\n") { "• $it" })
                        }
                    }
                }
            }

            item {
                Button(onClick = { onGraduate(true) }, enabled = state.canGraduate, modifier = Modifier.fillMaxWidth()) {
                    Text("ارفع استقلاليتي درجة")
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onImprove(true) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.busy) "جارٍ الإصلاح…" else "أصلح مهاراتي الفاشلة") }
                    OutlinedButton(
                        onClick = { onImprove(false) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("تجربة بلا تطبيق") }
                }
            }

            if (state.message != null) {
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("النتيجة", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(state.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("كيف تُكتسب الاستقلالية", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "ASK ← BALANCED ← FULL، درجة واحدة في كل مرة.\n" +
                                "الشرط: 20 تشغيلًا بنجاح 80%+ من سجلّي الفعلي.\n" +
                                "سجل ضعيف (أقل من 50%) يخفض المستوى تلقائيًا.\n" +
                                "كل إصلاح يمر بالحاوية المغلقة، والنسخة المستقرة محفوظة دائمًا."
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

data class GrowthViewState(
    val autonomy: String = "ASK",
    val skills: Int = 0,
    val runs: Int = 0,
    val reliability: Float = 0f,
    val healthy: Int = 0,
    val failing: List<String> = emptyList(),
    val retired: List<String> = emptyList(),
    val evidence: String = "",
    val canGraduate: Boolean = false,
    val progress: Float = 0f,
    val busy: Boolean = false,
    val message: String? = null,
)
