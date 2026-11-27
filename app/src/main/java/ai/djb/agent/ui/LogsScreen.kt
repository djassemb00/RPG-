package ai.djb.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp

@Composable
fun LogsScreen(vm: AgentViewModel) {
    var version by remember { mutableIntStateOf(0) }
    val lines = remember(version) { vm.logs() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "سجل التدقيق (${lines.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    vm.clearLog()
                    version++
                }) { Text("مسح") }
            }
        }
        if (lines.isEmpty()) {
            item { MonoLabel("لا سجلات بعد. كل أداة ينفّذها المساعد تُسجَّل هنا.") }
        } else {
            items(lines.asReversed()) { raw ->
                Text(
                    text = prettify(raw),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun prettify(raw: String): String {
    return runCatching {
        val obj = org.json.JSONObject(raw)
        val kind = obj.optString("kind")
        val text = obj.optString("text")
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(obj.optLong("at")))
        "$stamp [$kind] $text"
    }.getOrElse { raw }
}
