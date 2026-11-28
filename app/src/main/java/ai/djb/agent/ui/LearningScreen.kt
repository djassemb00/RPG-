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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Learning screen: the visible difference between "the app remembers" and "the model
 * learns". Lessons are immediate and local; the training export is the only honest route
 * to the second kind, and the screen says so plainly instead of implying more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(
    onBack: () -> Unit,
    state: LearningViewState,
    onRefresh: () -> Unit,
    onLearn: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التعلّم") },
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
                        Text("ما جمعته من تجربتي", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "تعلّم تلقائي: ${if (state.autoLearn) "مفعّل" else "متوقف"}\n" +
                                "دروس مستخلصة: ${state.lessons.size}\n" +
                                "أمثلة صالحة للتدريب: ${state.examples}\n" +
                                "متوسط طول الخطة: ${"%.1f".format(state.avgSteps)} خطوة"
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onLearn,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.busy) "…" else "استخلص درسًا") }
                    OutlinedButton(
                        onClick = onExport,
                        enabled = state.examples > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("صدّر بيانات تدريب") }
                }
            }

            if (state.lessons.isNotEmpty()) {
                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("دروسي", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onClear) { Text("مسح") }
                    }
                }
                state.lessons.forEachIndexed { index, lesson ->
                    item(key = "l$index") {
                        Card {
                            Column(Modifier.padding(14.dp)) {
                                Text("• $lesson", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
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
                        Text("ما الفرق؟", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "الدروس تُحقن في تخطيطي القادم فورًا — تعلّم محلي بلا تكلفة.\n" +
                                "بيانات التدريب (JSONL) تحوّل تجربتي إلى أوزان على مزوّدك — " +
                                "هذا هو التعلّم على طريقة LLM، والخبرة مصدرها عملك الحقيقي.\n\n" +
                                "حدّ صريح: لا تدريب على الهاتف. الملف يُصدَّر، والتدريب يحدث عند المزوّد بمفتاحك."
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

data class LearningViewState(
    val autoLearn: Boolean = true,
    val lessons: List<String> = emptyList(),
    val examples: Int = 0,
    val avgSteps: Double = 0.0,
    val busy: Boolean = false,
    val message: String? = null,
)
