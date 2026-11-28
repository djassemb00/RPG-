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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two halves of the same idea: what the agent noticed, and what it wants to change.
 *
 * Both are proposals. Nothing here is applied by the app itself — the screen exists so
 * a human can read a diff before any code changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfCodeScreen(
    onBack: () -> Unit,
    patterns: List<PatternView>,
    patches: List<PatchView>,
    sourceCount: Int,
    message: String?,
    onRefresh: () -> Unit,
    onExportPatch: (String) -> Unit,
    onAdoptPattern: (PatternView) -> Unit,
) {
    var openDiff by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("كودي وتعلّمي") },
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
                        Text("ما تكرّر عليّ", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        if (patterns.isEmpty()) {
                            MonoLabel("لا نمط متكرر بعد. أحتاج نفس التسلسل في مهمتين مختلفتين.")
                        } else {
                            MonoLabel("اكتشفت ${patterns.size} نمطًا من سجل عملي.")
                        }
                    }
                }
            }

            patterns.forEach { pattern ->
                item(key = "p-" + pattern.key) {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "${pattern.occurrences}× ${pattern.tools.joinToString(" ← ")}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onAdoptPattern(pattern) }) {
                                    Text("حوّلها إلى مهارة")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("رقع على كودي", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel("$sourceCount ملفًا مقروءًا · ${patches.size} رقعة")
                    }
                }
            }

            patches.forEach { patch ->
                item(key = patch.id) {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text(patch.file, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            Text(patch.reason, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            MonoLabel(
                                "${patch.id} · ${fmt.format(Date(patch.createdAt))} · " +
                                    if (patch.applied) "مطبّقة" else "بانتظار المراجعة"
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { openDiff = if (openDiff == patch.id) null else patch.id }
                                ) { Text(if (openDiff == patch.id) "إخفاء" else "عرض الفرق") }
                                OutlinedButton(onClick = { onExportPatch(patch.id) }) { Text("تصدير") }
                            }
                            if (openDiff == patch.id && patch.diff != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    patch.diff,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            if (message != null) {
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("النتيجة", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("الحد الصريح", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "التطبيق لا يستطيع تعديل نفسه وهو يعمل — الكود مُصرَّف داخل APK.\n" +
                                "ما يفعله: يقرأ مصدره الحقيقي، يجد الخلل، ويقترح diffًا دقيقًا.\n" +
                                "أنت (أو خط البناء) تطبّقه فيُبنى APK جديد. كل رقعة مرئية وقابلة للرفض."
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

data class PatternView(
    val key: String,
    val tools: List<String>,
    val occurrences: Int,
)

data class PatchView(
    val id: String,
    val file: String,
    val reason: String,
    val createdAt: Long,
    val applied: Boolean,
    val diff: String?,
)
