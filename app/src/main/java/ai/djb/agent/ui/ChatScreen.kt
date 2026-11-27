package ai.djb.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatScreen(
    state: UiState,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onGoSettings: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.feed.size) {
        if (state.feed.isNotEmpty()) listState.animateScrollToItem(state.feed.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {

        if (!state.configured) {
            Banner(
                text = "لم تضبط المزوّد بعد. أضف مفتاح API واسم الموديل للبدء.",
                actionLabel = "الإعدادات",
                onAction = onGoSettings,
            )
        } else if (!state.accessibilityOn) {
            Banner(
                text = "تحكم الشاشة غير مفعّل — لن يستطيع المساعد رؤية التطبيقات أو النقر فيها.",
                actionLabel = "تفعيل",
                onAction = onEnableAccessibility,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(state.feed.size) { index ->
                Bubble(state.feed[index])
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("اطلب شيئًا…") },
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (state.running) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "إيقاف", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            onSubmit(text)
                            input = ""
                        }
                    },
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "إرسال", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("يعمل…", style = MaterialTheme.typography.labelSmall)
                } else {
                    Dot(MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.skillsCount} مهارة · ${state.memoryCount} ذكرى · ${state.rulesCount} قاعدة",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            TextButton(onClick = onClear) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.height(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("تفريغ", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Banner(text: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onAction) { Text(actionLabel, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun Bubble(item: FeedItem) {
    val scheme = MaterialTheme.colorScheme
    val (bg, label) = when (item.kind) {
        FeedKind.USER -> scheme.primary.copy(alpha = 0.18f) to "أنت"
        FeedKind.AGENT -> scheme.surfaceVariant.copy(alpha = 0.5f) to ""
        FeedKind.TOOL -> scheme.surface.copy(alpha = 0.9f) to ""
        FeedKind.ANSWER -> scheme.secondary.copy(alpha = 0.18f) to "النتيجة"
        FeedKind.SYSTEM -> scheme.surfaceVariant.copy(alpha = 0.3f) to ""
        FeedKind.ERROR -> scheme.error.copy(alpha = 0.18f) to "خطأ"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
        }
        when (item.kind) {
            FeedKind.TOOL -> {
                Text(
                    item.title,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.secondary,
                )
                if (item.body.isNotBlank()) {
                    Text(
                        item.body,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 12,
                    )
                }
            }
            FeedKind.SYSTEM -> Text(
                item.title,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            else -> Text(item.body.ifBlank { item.title }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
