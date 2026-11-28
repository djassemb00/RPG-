package ai.djb.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AdbScreen(
    state: UiState,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onProbe: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("5555") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("ADB اللاسلكي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "اتصال ADB حقيقي داخل التطبيق (بلا ملف adb وبلا كمبيوتر) عبر مكتبة dadb. " +
                    "يمنح تحكمًا أعمق بكثير من إمكانية الوصول: نقر وكتابة في أي تطبيق، قراءة حالة النظام، وإدارة الحزم.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(
                        when {
                            state.adbConnected -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.adbConnected) "متصل — ${state.adbTarget}" else "غير متصل",
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.adbBusy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                    }
                }
                if (state.adbLastError.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "آخر خطأ: ${state.adbLastError}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(6.dp))
                MonoLabel("مفتاح التطبيق (SHA-256): ${state.adbKeyFingerprint}")
            }
        }

        item {
            SectionCard {
                Text("خطوات التفعيل", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "١) الإعدادات ← حول الهاتف ← اضغط «رقم الإصدار» 7 مرات لتفعيل خيارات المطوّر.\n" +
                        "٢) خيارات المطوّر ← فعّل «التصحيح اللاسلكي».\n" +
                        "٣) داخل شاشته اضغط «إقران الجهاز باستخدام رمز الإقران» — هذا لمرة واحدة فقط " +
                        "(التطبيق لا يستطيع الإقران برمجيًا، وهي حماية من أندرويد).\n" +
                        "٤) ارجع لشاشة التصحيح اللاسلكي وانسخ منفذ «IP address & Port» هنا واضغط اتصل.\n" +
                        "٥) سيظهر طلب إذن على الجهاز لبصمتنا أعلاه — اسمح.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onOpenSettings) { Text("فتح خيارات المطوّر") }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("المضيف") },
                    singleLine = true,
                    modifier = Modifier.width(150.dp),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("المنفذ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onConnect(host.trim(), port.toIntOrNull() ?: 5555) },
                    enabled = !state.adbBusy,
                ) { Text("اتصل") }
                OutlinedButton(onClick = onProbe, enabled = state.adbConnected) { Text("فحص الجهاز") }
                OutlinedButton(onClick = onDisconnect, enabled = state.adbConnected) { Text("قطع") }
            }
        }

        state.adbResult?.let { result ->
            item {
                SectionCard {
                    Text("النتيجة", fontWeight = FontWeight.SemiBold)
                    Text(
                        result,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard {
                Text("حدود صريحة", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "• الإقران يحتاج لمسة يدوية مرة واحدة — أندرويد يمنع التطبيقات من فعله.\n" +
                        "• لا root: الصلاحيات هي صلاحيات shell.\n" +
                        "• `input text` لا يدعم العربية على معظم الأجهزة؛ للعربية استخدم الكتابة عبر إمكانية الوصول.\n" +
                        "• عند إعادة تشغيل الهاتف يتوقف التصحيح اللاسلكي ويحتاج إعادة تفعيل.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
