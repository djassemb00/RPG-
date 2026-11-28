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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.djb.agent.core.Autonomy

@Composable
fun SettingsScreen(
    state: UiState,
    onOpenAdb: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenSelfCode: () -> Unit = {},
    onOpenUpgrade: () -> Unit = {},
    onSaveBehaviour: (String, Autonomy, Boolean, Int, String, Boolean) -> Unit,
    onEnableAccessibility: () -> Unit,
) {
    var name by remember(state.agentName) { mutableStateOf(state.agentName) }
    var autonomy by remember(state.autonomy) { mutableStateOf(state.autonomy) }
    var selfImprove by remember(state.selfImprove) { mutableStateOf(state.selfImprove) }
    var steps by remember(state.maxSteps) { mutableStateOf(state.maxSteps.toString()) }
    var extra by remember(state.extraInstruction) { mutableStateOf(state.extraInstruction) }
    var vision by remember(state.visionEnabled) { mutableStateOf(state.visionEnabled) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("المزوّد والموديل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "أُنتقلت إدارة المزوّدات إلى تبويب «المزوّدون» (مع التبديل التلقائي).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionCard {
                MonoLabel("سلسلة الطلب الحالية")
                Text(state.providerChain, fontWeight = FontWeight.SemiBold)
                if (!state.configured) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "لا مزوّد مضبوط بعد — أضف واحدًا من تبويب المزوّدين.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item {
            Text("السلوك والصلاحيات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("اسم المساعد") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("درجة الاستقلالية", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Autonomy.entries.forEach { a ->
                    FilterChip(
                        selected = autonomy == a,
                        onClick = { autonomy = a },
                        label = {
                            Text(
                                when (a) {
                                    Autonomy.ASK -> "اسألني"
                                    Autonomy.BALANCED -> "متوازن"
                                    Autonomy.FULL -> "كامل"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
            MonoLabel(
                when (autonomy) {
                    Autonomy.ASK -> "كل نقرة/كتابة/أمر حسّاس ينتظر موافقتك."
                    Autonomy.BALANCED -> "الإجراءات المتوسطة تلقائية، الخطيرة تسأل."
                    Autonomy.FULL -> "كل شيء تلقائي — استخدمه فقط وأنت تراقب الشاشة."
                }
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("التطوير الذاتي", style = MaterialTheme.typography.labelLarge)
                    MonoLabel("السماح للمساعد بإنشاء مهارات وقواعد لنفسه.")
                }
                Switch(checked = selfImprove, onCheckedChange = { selfImprove = it })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("الرؤية البصرية", style = MaterialTheme.typography.labelLarge)
                    MonoLabel("إرسال لقطات الشاشة للموديل (يحتاج موديلًا بصريًا: gpt-4o / gemini).")
                }
                Switch(checked = vision, onCheckedChange = { vision = it })
            }
        }
        item {
            OutlinedTextField(
                value = steps,
                onValueChange = { steps = it.filter(Char::isDigit).take(2) },
                label = { Text("أقصى عدد خطوات للمهمة") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(220.dp),
            )
        }
        item {
            OutlinedTextField(
                value = extra,
                onValueChange = { extra = it },
                label = { Text("تعليمات إضافية دائمة") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = {
                    onSaveBehaviour(
                        name,
                        autonomy,
                        selfImprove,
                        steps.toIntOrNull() ?: 12,
                        extra,
                        vision,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("حفظ السلوك") }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item {
            Text("أدوات متقدمة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(
                        if (state.adbConnected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ADB اللاسلكي", fontWeight = FontWeight.SemiBold)
                        MonoLabel(
                            if (state.adbConnected) "متصل — ${state.adbTarget}"
                            else "غير متصل · تحكم أعمق بعد الإقران"
                        )
                    }
                    TextButton(onClick = onOpenAdb) { Text("فتح") }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("سجل التدقيق", fontWeight = FontWeight.SemiBold)
                        MonoLabel("كل استدعاء أداة ونتيجته")
                    }
                    TextButton(onClick = onOpenLogs) { Text("فتح") }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("النسخ الاحتياطي", fontWeight = FontWeight.SemiBold)
                        MonoLabel("صدّر المهارات والذاكرة والإعدادات أو استوردها")
                    }
                    TextButton(onClick = onOpenBackup) { Text("فتح") }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("كودي وتعلّمي", fontWeight = FontWeight.SemiBold)
                        MonoLabel("اقتراحات مهارات من تكرارك + رقع على كود التطبيق")
                    }
                    TextButton(onClick = onOpenSelfCode) { Text("فتح") }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("النسخ والصمّام", fontWeight = FontWeight.SemiBold)
                        MonoLabel("حاوية التحقق · استعادة النسخة المستقرة")
                    }
                    TextButton(onClick = onOpenUpgrade) { Text("فتح") }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item {
            Text("صلاحية التحكم في الشاشة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(
                        if (state.accessibilityOn) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.accessibilityOn) "مفعّلة" else "غير مفعّلة",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "بدونها لا يستطيع المساعد قراءة الشاشة، النقر، أو الكتابة في التطبيقات. " +
                        "الصلاحية تُمنح من إعدادات النظام مرة واحدة، ويمكن سحبها في أي وقت.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onEnableAccessibility) { Text("فتح إعدادات إمكانية الوصول") }
            }
        }

        item {
            SectionCard {
                Text("حدود تقنية صريحة", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "• لا يمكن للتطبيق تعديل كوده (APK) وهو يعمل؛ أي قدرة جديدة تحتاج إصدارًا جديدًا.\n" +
                        "• ADB/root غير مضمونين: التنفيذ بصلاحيات التطبيق فقط.\n" +
                        "• بعض المفاتيح (واي فاي/بلوتوث) تفتح صفحة الإعدادات بدل التبديل المباشر على أندرويد 13+.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
