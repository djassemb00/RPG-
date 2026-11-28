package ai.djb.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.djb.agent.core.Repeat
import ai.djb.agent.core.ScheduledTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen(
    tasks: List<ScheduledTask>,
    onAdd: (String, String, Int, Int, Repeat) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRunNow: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("9") }
    var minute by remember { mutableStateOf("0") }
    var repeat by remember { mutableStateOf(Repeat.DAILY) }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("مهام مجدولة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "تُنفَّذ في الخلفية بلا فتح التطبيق. القيود الواقعية: أندرويد يفرض حدًا أدنى 15 دقيقة، " +
                    "والأدوات التي تحتاج لمس الشاشة تُرفض في الخلفية (لا مستخدم للموافقة).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (tasks.isEmpty()) {
            item {
                SectionCard {
                    Text("لا مهام مجدولة بعد.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            items(tasks) { task ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(task.title, fontWeight = FontWeight.SemiBold)
                            MonoLabel(task.repeat.label() + " · " +
                                String.format(Locale.US, "%02d:%02d", task.hour, task.minute))
                        }
                        Switch(
                            checked = task.enabled,
                            onCheckedChange = { onToggle(task.id, it) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(task.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Spacer(Modifier.height(4.dp))
                    MonoLabel(
                        "القادم=" + (if (task.nextRunAt > 0) fmt.format(Date(task.nextRunAt)) else "—") +
                            " · تشغيل=${task.runCount} · فشل=${task.failCount}"
                    )
                    if (task.lastResult.isNotBlank()) {
                        Text(
                            "آخر نتيجة: ${task.lastResult.take(120)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (task.lastOk) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onRunNow(task.id) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "شغّل الآن")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onDelete(task.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "حذف")
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text("إضافة مهمة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("الاسم") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("المهمة التي سينفّذها الوكيل") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = hour,
                    onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                    label = { Text("ساعة") },
                    singleLine = true,
                    modifier = Modifier.width(90.dp),
                )
                OutlinedTextField(
                    value = minute,
                    onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                    label = { Text("دقيقة") },
                    singleLine = true,
                    modifier = Modifier.width(90.dp),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Repeat.entries.forEach { r ->
                    AssistChip(
                        onClick = { repeat = r },
                        label = {
                            Text(
                                (if (repeat == r) "✓ " else "") + r.label(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        }
        item {
            AssistChip(
                onClick = {
                    if (title.isNotBlank() && prompt.isNotBlank()) {
                        onAdd(
                            title.trim(),
                            prompt.trim(),
                            hour.toIntOrNull()?.coerceIn(0, 23) ?: 9,
                            minute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                            repeat,
                        )
                        title = ""
                        prompt = ""
                        repeat = Repeat.DAILY
                    }
                },
                label = { Text("جدولة المهمة", style = MaterialTheme.typography.labelMedium) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
