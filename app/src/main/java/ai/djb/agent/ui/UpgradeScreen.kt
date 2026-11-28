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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The safety valve's control panel.
 *
 * Rollback must be the easiest thing on this screen to reach — if a bad upgrade makes
 * the agent misbehave, the user should not have to hunt for the way out. So it is a
 * full-width button in the error colour, always enabled, with no confirmation buried
 * deeper than one dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(
    onBack: () -> Unit,
    state: UpgradeViewState,
    onRefresh: () -> Unit,
    onRunHealth: () -> Unit,
    onRollback: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    var confirmRollback by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("النسخ والصمّام") },
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
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (state.healthOk) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.errorContainer
                )) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (state.healthOk) "الحالة: سليمة" else "الحالة: خلل",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(state.healthSummary.ifBlank { "لم يُشغَّل الفحص بعد" })
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRunHealth) { Text("تشغيل الفحص الآن") }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("النسخة الحالية", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "مستقرة: ${state.stableId ?: "—"}\n" +
                                "مرشّحة: ${state.candidateId ?: "—"}"
                        )
                        if (state.lastRollbackAt > 0) {
                            Spacer(Modifier.height(6.dp))
                            MonoLabel(
                                "آخر استعادة: ${fmt.format(Date(state.lastRollbackAt))}\n" +
                                    state.lastRollbackReason
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { confirmRollback = true },
                    enabled = state.stableId != null && state.candidateId != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("استعادة النسخة المستقرة")
                }
            }

            if (state.versions.size > 1) {
                item {
                    Text(
                        "نسخ محفوظة",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                state.versions.forEach { version ->
                    item(key = version.id) {
                        Card {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(version.label, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    if (version.id == state.stableId) {
                                        MonoLabel("مستقرة")
                                    } else if (version.id == state.candidateId) {
                                        MonoLabel("مرشّحة")
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                MonoLabel(
                                    "${version.id} · ${fmt.format(Date(version.createdAt))}\n" +
                                        version.detail
                                )
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = { onRestore(version.id) }) {
                                    Text("استعادة هذه النسخة")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("كيف يعمل الصمّام", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "1. أي تعديل يمر أولًا بالحاوية المغلقة (8 فحوص) — لا ترقية بلا اجتياز.\n" +
                                "2. قبل الترقية تُحفظ نسخة مستقرة كاملة (مهارات + قواعد + إعدادات).\n" +
                                "3. بعد الترقية تُراقَب السلامة؛ فشلان متتاليان يعيدان النسخة المستقرة تلقائيًا.\n" +
                                "4. يمكنك الاستعادة يدويًا في أي وقت — الزر الأحمر أعلاه."
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (confirmRollback) {
        AlertDialog(
            onDismissRequest = { confirmRollback = false },
            title = { Text("استعادة النسخة المستقرة؟") },
            text = {
                Text(
                    "سيُلغى تأثير الترقية الأخيرة وتعود المهارات والقواعد والإعدادات كما كانت. " +
                        "لن يُحذف شيء من سجل الرقع."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRollback = false
                    onRollback("استعادة يدوية من المستخدم")
                }) { Text("استعادة") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRollback = false }) { Text("إلغاء") }
            },
        )
    }
}

data class UpgradeVersionView(
    val id: String,
    val label: String,
    val createdAt: Long,
    val detail: String,
)

data class UpgradeViewState(
    val healthOk: Boolean = true,
    val healthSummary: String = "",
    val stableId: String? = null,
    val candidateId: String? = null,
    val lastRollbackAt: Long = 0,
    val lastRollbackReason: String = "",
    val versions: List<UpgradeVersionView> = emptyList(),
)
