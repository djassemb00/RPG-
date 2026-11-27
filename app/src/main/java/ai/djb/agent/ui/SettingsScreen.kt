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
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.djb.agent.core.Autonomy

private data class Provider(val label: String, val base: String, val model: String)

private val providers = listOf(
    Provider("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Provider("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-2.0-flash-001"),
    Provider("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    Provider("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    Provider("Gemini (compat)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
    Provider("Ollama (LAN)", "http://192.168.1.10:11434/v1", "qwen2.5:7b"),
)

@Composable
fun SettingsScreen(
    state: UiState,
    onSaveConnection: (String, String, String) -> Unit,
    onSaveBehaviour: (String, Autonomy, Boolean, Int, String) -> Unit,
    onEnableAccessibility: () -> Unit,
) {
    var baseUrl by remember(state.apiBaseUrl) { mutableStateOf(state.apiBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember(state.model) { mutableStateOf(state.model) }

    var name by remember(state.agentName) { mutableStateOf(state.agentName) }
    var autonomy by remember(state.autonomy) { mutableStateOf(state.autonomy) }
    var selfImprove by remember(state.selfImprove) { mutableStateOf(state.selfImprove) }
    var steps by remember(state.maxSteps) { mutableStateOf(state.maxSteps.toString()) }
    var extra by remember(state.extraInstruction) { mutableStateOf(state.extraInstruction) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("المزوّد والموديل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "يُخزَّن المفتاح داخل تخزين التطبيق الخاص فقط ولا يُرسل لأي جهة غير المزوّد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                providers.take(3).forEach { p ->
                    AssistChip(
                        onClick = { baseUrl = p.base; model = p.model },
                        label = { Text(p.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                providers.drop(3).forEach { p ->
                    AssistChip(
                        onClick = { baseUrl = p.base; model = p.model },
                        label = { Text(p.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (state.hasApiKey) "API Key (محفوظ — اكتب لاستبداله)" else "API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = { onSaveConnection(baseUrl, apiKey, model); apiKey = "" },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("حفظ الاتصال") }
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
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("حفظ السلوك") }
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
