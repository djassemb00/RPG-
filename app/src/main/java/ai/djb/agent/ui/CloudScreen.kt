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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Cloud screen. The token field is masked and never echoed back into a result string —
 * a shared secret that appears in a status card is a secret that ends up in a screenshot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(
    onBack: () -> Unit,
    state: CloudViewState,
    onSave: (String, String, String) -> Unit,
    onProbe: () -> Unit,
    onPush: () -> Unit,
    onSaveGitHub: (String, String, String) -> Unit,
) {
    var url by remember(state.url) { mutableStateOf(state.url) }
    var token by remember(state.token) { mutableStateOf(state.token) }
    var client by remember(state.clientId) { mutableStateOf(state.clientId) }
    var ghToken by remember(state.githubToken) { mutableStateOf(state.githubToken) }
    var ghRepo by remember(state.githubRepo) { mutableStateOf(state.githubRepo) }
    var ghBranch by remember(state.githubBranch) { mutableStateOf(state.githubBranch) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("السحابة والإنترنت") },
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
                        Text("حالة العامل السحابي", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(state.status.ifBlank { "لم يُفحص بعد" })
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("رابط العامل (https://…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("WORKER_TOKEN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = client,
                    onValueChange = { client = it },
                    label = { Text("معرّف الجهاز (للعقل المشترك)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSave(url, token, client) },
                        modifier = Modifier.weight(1f),
                    ) { Text("حفظ") }
                    OutlinedButton(
                        onClick = onProbe,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.busy) "…" else "فحص") }
                }
            }

            item {
                OutlinedButton(
                    onClick = onPush,
                    enabled = !state.busy && state.url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("ادفع مهاراتي ودروسي للسحابة") }
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

            item { Spacer(Modifier.height(8.dp)) }
            item {
                Text("البناء الذاتي (GitHub)", style = MaterialTheme.typography.titleMedium)
            }
            item {
                MonoLabel(
                    "بعد ضبطه يستطيع الوكيل كتابة كود جديد وطلبه بنفسه، ويقرأ أخطاء المُصرِّف " +
                        "الحقيقية ويصحّحها. لا يُنشر شيء إن فشل البناء."
                )
            }
            item {
                OutlinedTextField(
                    value = ghToken,
                    onValueChange = { ghToken = it },
                    label = { Text("توكن GitHub (Actions + Contents)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = ghRepo,
                    onValueChange = { ghRepo = it },
                    label = { Text("المستودع owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = ghBranch,
                    onValueChange = { ghBranch = it },
                    label = { Text("الفرع") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = { onSaveGitHub(ghToken, ghRepo, ghBranch) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("حفظ إعدادات البناء") }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("ما تحصل عليه", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "① أدوات إنترنت — تعمل الآن بلا سيرفر: web_fetch، web_search، http_get.\n" +
                                "② عامل سحابي — مهام تُنفَّذ والهاتف مغلق (مجلد worker في المستودع).\n" +
                                "③ عقل مشترك — المهارات والدروس تُزامَن بين أجهزتك.\n" +
                                "④ وكيل مستضاف — نفس العامل، يعمل 24/7 بلا هاتف.\n\n" +
                                "الأمان: الشبكة المحلية والجهاز محجوبان، الكوكيز لا تُرسل، والمحتوى " +
                                "المقروء موسوم «غير موثوق» فلا يُنفَّذ كأمر. الرمز لا يُصدَّر في أي نسخة احتياطية."
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

data class CloudViewState(
    val githubToken: String = "",
    val githubRepo: String = "",
    val githubBranch: String = "main",
    val url: String = "",
    val token: String = "",
    val clientId: String = "primary",
    val status: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)
