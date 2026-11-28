package ai.djb.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.djb.agent.core.ProviderPreset
import ai.djb.agent.core.AgentPhase

@Composable
fun ProvidersScreen(
    state: UiState,
    onAdd: (String, String, String, String, String) -> Unit,
    onRemove: (String, String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
    onDiagnose: (String) -> Unit,
    onLoadModels: (String) -> Unit,
    onPickModel: (String, String) -> Unit,
    onAutoFix: (String) -> Unit,
    onSetRole: (String, AgentPhase) -> Unit,
    onSetPlanModel: (String, String) -> Unit,
) {
    var editingPlanModel by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ProviderPreset?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "المزوّدون والتبديل التلقائي",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "أضف مفاتيحك الخاصة من الطبقات المجانية. عند تجاوز حد مزوّد (429) أو تعطّله، " +
                    "ينتقل الطلب تلقائيًا للتالي في السلسلة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionCard {
                MonoLabel("التوجيه الحالي:")
                Text(state.routingSummary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "التخطيط = أول قرار في المهمة (رخيص). التنفيذ = خطوات الأدوات (مكلف). " +
                        "يمكنك جعل مزوّد سريع للتخطيط وآخر قوي للتنفيذ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.providerTestResult != null) {
            item {
                SectionCard {
                    Text("نتيجة الاختبار", fontWeight = FontWeight.SemiBold)
                    Text(state.providerTestResult, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Text("لديك (${state.providers.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        if (state.providers.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        "لا مزوّد بعد. اضغط على أي مزوّد من القائمة أدناه ← ألصق مفتاحك ← حفظ.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            items(state.providers) { slot ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(
                            when {
                                !slot.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                slot.failCount > 0 && slot.successCount == 0 -> MaterialTheme.colorScheme.error
                                slot.configured -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(slot.label, fontWeight = FontWeight.SemiBold)
                            MonoLabel("${slot.model} · ${slot.baseUrl}")
                        }
                        Switch(
                            checked = slot.enabled,
                            onCheckedChange = { onToggle(slot.presetId, it) },
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    MonoLabel(
                        "نجاح=${slot.successCount} · فشل=${slot.failCount}" +
                            if (slot.hasKey) " · المفتاح محفوظ" else " · بلا مفتاح"
                    )
                    slot.lastError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "آخر خطأ: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    MonoLabel("الدور:")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AgentPhase.entries.forEach { role ->
                            FilterChip(
                                selected = slot.role == role,
                                onClick = { onSetRole(slot.presetId, role) },
                                label = { Text(role.label(), style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel(
                            "موديل التخطيط: " + slot.planModel.ifBlank { "(نفس الأساسي)" },
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = { editingPlanModel = slot.presetId },
                            label = { Text("تعديل", style = MaterialTheme.typography.labelSmall) },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { onTest(slot.presetId) }) {
                            if (state.providerTesting == slot.presetId) {
                                CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text("اختبار", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        OutlinedButton(
                            onClick = { onLoadModels(slot.presetId) },
                            enabled = state.modelLoadingFor != slot.presetId,
                        ) {
                            if (state.modelLoadingFor == slot.presetId) {
                                CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text("الموديلات", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        OutlinedButton(onClick = { onAutoFix(slot.presetId) }) {
                            Text("إصلاح تلقائي", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = { onDiagnose(slot.presetId) }) {
                            Text("تشخيص", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    val models = state.modelLists[slot.presetId]
                    if (models != null) {
                        Spacer(Modifier.height(6.dp))
                        MonoLabel("موديلات ${slot.label} المتاحة (${models.size}) — اضغط للاختيار:")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            models.forEach { id ->
                                Text(
                                    text = (if (id == slot.model) "✓ " else "• ") + id,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (id == slot.model) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPickModel(slot.presetId, id) }
                                        .padding(vertical = 3.dp),
                                )
                            }
                        }
                    }
                    state.modelListError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMove(slot.presetId, true) }) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "أعلى")
                        }
                        IconButton(onClick = { onMove(slot.presetId, false) }) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "أسفل")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onRemove(slot.presetId, slot.label) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "حذف")
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text("إضافة مزوّد", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "هذه طبقات مجانية رسمية — تُنشئ مفتاحك بنفسك. لا مفاتيح مشتركة ولا مسرّبة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(ProviderPreset.ALL) { preset ->
            val already = state.providers.any { it.presetId == preset.id }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.label, fontWeight = FontWeight.SemiBold)
                        MonoLabel(preset.freeTier)
                    }
                    if (already) {
                        MonoLabel("مضاف")
                    } else {
                        AssistChip(
                            onClick = { editing = preset },
                            label = { Text("إضافة", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    editingPlanModel?.let { presetId ->
        val slot = state.providers.firstOrNull { it.presetId == presetId }
        var draft by remember(presetId) { mutableStateOf(slot?.planModel.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingPlanModel = null },
            title = { Text("موديل التخطيط — ${slot?.label.orEmpty()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "موديل رخيص/سريع لهذه المرحلة. اتركه فارغًا لاستخدام الموديل الأساسي.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val available = state.modelLists[presetId].orEmpty()
                    if (available.isNotEmpty()) {
                        Text(
                            "من القائمة الحية: " + available.take(4).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetPlanModel(presetId, draft)
                    editingPlanModel = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { editingPlanModel = null }) { Text("إلغاء") } },
        )
    }

    editing?.let { preset ->
        AddProviderDialog(
            preset = preset,
            onDismiss = { editing = null },
            onConfirm = { baseUrl, model, key ->
                onAdd(preset.id, preset.label, baseUrl, model, key)
                editing = null
            },
        )
    }
}

@Composable
private fun AddProviderDialog(
    preset: ProviderPreset,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var baseUrl by remember(preset) { mutableStateOf(preset.baseUrl) }
    var model by remember(preset) { mutableStateOf(preset.defaultModel) }
    var key by remember(preset) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة ${preset.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preset.freeTier, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(
                    "احصل على مفتاحك من: ${preset.signupUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "المفتاح يُخزَّن داخل التطبيق فقط ولا يظهر في السجلات.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(baseUrl, model, key) },
                enabled = key.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
