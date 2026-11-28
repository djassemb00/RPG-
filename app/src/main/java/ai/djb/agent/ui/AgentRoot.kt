package ai.djb.agent.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.djb.agent.AgentApp
import ai.djb.agent.core.Risk

private enum class Tab { CHAT, SKILLS, TASKS, PROVIDERS, ADB, SETTINGS, LOGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentRoot(app: AgentApp) {
    val vm: AgentViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                AgentViewModel(app) as T
        }
    )
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.CHAT) }
    var settingsVersion by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Re-check accessibility + config whenever the app comes back to the foreground.
    LaunchedEffect(tab, settingsVersion) { vm.onResume() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column {
                        Text(state.agentName, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !state.configured -> "غير مكوّن — افتح الإعدادات"
                                state.accessibilityOn -> "جاهز · تحكم الشاشة مفعّل"
                                else -> "جاهز · بلا تحكم بالشاشة"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == Tab.CHAT,
                    onClick = { tab = Tab.CHAT },
                    icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                    label = { Text("المحادثة") },
                )
                NavigationBarItem(
                    selected = tab == Tab.SKILLS,
                    onClick = { tab = Tab.SKILLS },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text("المهارات") },
                )
                NavigationBarItem(
                    selected = tab == Tab.TASKS,
                    onClick = { tab = Tab.TASKS },
                    icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                    label = { Text("المهام") },
                )
                NavigationBarItem(
                    selected = tab == Tab.PROVIDERS,
                    onClick = { tab = Tab.PROVIDERS },
                    icon = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    label = { Text("المزوّدون") },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("الإعدادات") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.CHAT -> ChatScreen(
                    state = state,
                    onSubmit = vm::submit,
                    onStop = vm::stop,
                    onClear = vm::clearFeed,
                    onEnableAccessibility = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onGoSettings = { tab = Tab.SETTINGS },
                )
                Tab.SKILLS -> SkillsScreen(
                    vm = vm,
                    state = state,
                    onChanged = { settingsVersion++ },
                )
                Tab.TASKS -> TasksScreen(
                    tasks = state.tasks,
                    onAdd = { title, prompt, hour, minute, repeat ->
                        vm.addTask(title, prompt, hour, minute, repeat)
                        settingsVersion++
                    },
                    onToggle = { id, on -> vm.toggleTask(id, on); settingsVersion++ },
                    onDelete = { id -> vm.deleteTask(id); settingsVersion++ },
                    onRunNow = { id -> vm.runTaskNow(id) },
                )
                Tab.PROVIDERS -> ProvidersScreen(
                    state = state,
                    onAdd = { id, label, base, model, key ->
                        vm.addOrUpdateProvider(id, label, base, model, key)
                        settingsVersion++
                    },
                    onRemove = { id, label -> vm.removeProvider(id, label); settingsVersion++ },
                    onToggle = { id, on -> vm.setProviderEnabled(id, on); settingsVersion++ },
                    onMove = { id, up -> vm.moveProvider(id, up); settingsVersion++ },
                    onTest = { id -> vm.testProvider(id) },
                    onDiagnose = { id -> vm.diagnose(id) },
                    onLoadModels = { id -> vm.loadModels(id) },
                    onPickModel = { id, model -> vm.setModel(id, model); settingsVersion++ },
                    onAutoFix = { id -> vm.autoFixModel(id); settingsVersion++ },
                    onSetRole = { id, role -> vm.setProviderRole(id, role); settingsVersion++ },
                    onSetPlanModel = { id, model -> vm.setPlanModel(id, model); settingsVersion++ },
                )
                Tab.ADB -> AdbScreen(
                    state = state,
                    onConnect = { host, port -> vm.adbConnect(host, port); settingsVersion++ },
                    onDisconnect = { vm.adbDisconnect(); settingsVersion++ },
                    onProbe = { vm.adbProbe() },
                    onOpenSettings = { vm.adbOpenWirelessSettings() },
                    onReadScreen = { vm.adbReadScreen() },
                    onScreenshot = { vm.adbScreenshot() },
                )
                Tab.SETTINGS -> SettingsScreen(
                    state = state,
                    onOpenAdb = { tab = Tab.ADB },
                    onOpenLogs = { tab = Tab.LOGS },
                    onSaveBehaviour = { name, autonomy, selfImprove, steps, extra, vision ->
                        vm.saveBehaviour(name, autonomy, selfImprove, steps, extra, vision)
                        settingsVersion++
                    },
                    onEnableAccessibility = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
                Tab.LOGS -> LogsScreen(vm)
            }
        }
    }

    state.pending?.let { pending ->
        val what = pending.args.take(400)
        val title = when (pending.risk) {
            Risk.HIGH -> "إجراء عالي الخطورة"
            Risk.MEDIUM -> "إجراء يحتاج موافقة"
            Risk.LOW -> "تأكيد"
        }
        AlertDialog(
            onDismissRequest = { vm.decide(false) },
            title = { Text(title) },
            text = {
                Column {
                    Text("الأداة: ${pending.tool}", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    MonoLabel(what)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "راجع المعاملات قبل السماح. الرفض يوقف هذا الإجراء فقط.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.decide(true) }) { Text("اسمح") }
            },
            dismissButton = {
                TextButton(onClick = { vm.decide(false) }) { Text("ارفض") }
            },
        )
    }
}
