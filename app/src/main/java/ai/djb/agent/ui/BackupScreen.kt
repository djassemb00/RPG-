package ai.djb.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Backup screen. The point is not the buttons — it is telling the user honestly what
 * is inside the file and what is not (keys, the ADB private key), so a backup is
 * understood rather than trusted blindly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    lastMessage: String?,
    exporting: Boolean,
    counts: BackupCounts,
    onExport: (Boolean) -> Unit,
    onShareLast: () -> Unit,
    onImport: () -> Unit,
) {
    var includeKeys by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("النسخ الاحتياطي") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
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
                        Text("ما سيُصدَّر الآن", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "${counts.skills} مهارة · ${counts.memory} ذكرى · ${counts.rules} قاعدة\n" +
                                "${counts.tasks} مهمة · ${counts.providers} مزوّد · الإعدادات"
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("تضمين مفاتيح API", style = MaterialTheme.typography.labelLarge)
                        MonoLabel(
                            if (includeKeys) "الملف سيحتوي مفاتيحك — لا تشاركه."
                            else "آمن للمشاركة. ستحتاج إدخال المفاتيح بعد الاستيراد."
                        )
                    }
                    Switch(checked = includeKeys, onCheckedChange = { includeKeys = it })
                }
            }

            item {
                Button(
                    onClick = { onExport(includeKeys) },
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (exporting) "جارٍ التصدير…" else "تصدير نسخة")
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onShareLast,
                        enabled = lastMessage != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("مشاركة الملف") }
                    OutlinedButton(
                        onClick = onImport,
                        enabled = !exporting,
                        modifier = Modifier.weight(1f),
                    ) { Text("استيراد ملف") }
                }
            }

            if (lastMessage != null) {
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("النتيجة", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(lastMessage, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("حدود صريحة", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "• الاستيراد دمج لا استبدال: لا يُحذف شيء موجود.\n" +
                                "• مفتاح ADB الخاص لا يُصدَّر أبدًا — أعد الإقران على الجهاز الجديد.\n" +
                                "• سجل المحادثات ليس جزءًا من النسخة (خصوصية).\n" +
                                "• المهام المستوردة تُعاد جدولتها عند فتح التطبيق."
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

data class BackupCounts(
    val skills: Int = 0,
    val memory: Int = 0,
    val rules: Int = 0,
    val tasks: Int = 0,
    val providers: Int = 0,
)
