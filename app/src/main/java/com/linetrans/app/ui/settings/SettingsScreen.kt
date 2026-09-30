package com.linetrans.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linetrans.app.data.DocRepository
import com.linetrans.app.data.SettingsRepository
import com.linetrans.app.data.StorageManager
import com.linetrans.app.data.WordbookRepository
import com.linetrans.app.ai.LocalDictionary
import com.linetrans.app.ui.Motion
import com.linetrans.app.model.BillingConfig
import com.linetrans.app.model.ModelConfig
import com.linetrans.app.model.PromptTemplates
import com.linetrans.app.model.ProviderConfig
import com.linetrans.app.model.ProviderType
import kotlinx.coroutines.launch
import java.util.UUID

private enum class SettingsTab(val label: String) {
    AI("AI 翻译"),
    UI("界面"),
    DATA("数据"),
    ADVANCED("高级"),
    ABOUT("关于")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(SettingsTab.AI) }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                SettingsTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val offset = if (forward) 1 else -1
                    (slideInHorizontally(Motion.enter(Motion.MEDIUM)) { it / 6 * offset } +
                        fadeIn(animationSpec = Motion.enter(Motion.MEDIUM))) togetherWith
                        (slideOutHorizontally(Motion.exit(Motion.MEDIUM - 60)) { -it / 10 * offset } +
                            fadeOut(animationSpec = Motion.exit(Motion.MEDIUM - 60)))
                },
                label = "settings-tab"
            ) { current ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (current) {
                        SettingsTab.AI -> aiTab(context, ::notify)
                        SettingsTab.UI -> uiTab()
                        SettingsTab.DATA -> dataTab(context, ::notify)
                        SettingsTab.ADVANCED -> advancedTab(context, ::notify)
                        SettingsTab.ABOUT -> aboutTab(context)
                    }
                }
            }
        }
    }
}

private typealias Notify = (String) -> Unit

// ———————————————————————— AI 翻译 ————————————————————————

private fun androidx.compose.foundation.lazy.LazyListScope.aiTab(context: Context, notify: Notify) {
    val settings = SettingsRepository.settings

    item {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("当前模型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    settings.activeModel?.name ?: "尚未选择模型",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    settings.activeProvider?.let { it.name + " · " + it.type.label } ?: "请先添加 API 提供商",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    item {
        SectionCard(
            title = "API 提供商",
            subtitle = "支持 OpenAI 兼容 / Anthropic / 自定义接口",
            icon = Icons.Default.Sync
        ) {
            if (settings.providers.isEmpty()) {
                Text(
                    "还没有配置服务商，添加后即可使用 AI 翻译。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            settings.providers.forEach { provider ->
                ProviderRow(
                    provider = provider,
                    active = settings.activeProviderId == provider.id,
                    onSelect = { SettingsRepository.update { it.copy(activeProviderId = provider.id, activeModelId = provider.models.firstOrNull()?.id ?: "") } },
                    onDelete = { SettingsRepository.update { s ->
                        s.providers.removeAll { it.id == provider.id }
                        if (s.activeProviderId == provider.id) s.activeProviderId = s.providers.firstOrNull()?.id ?: ""
                        if (s.activeModel == null) s.activeModelId = s.activeProvider?.models?.firstOrNull()?.id ?: ""
                        s
                    } }
                )
            }
            Spacer(Modifier.height(8.dp))
            ProviderDialogHost(notify = notify)
        }
    }

    if (settings.activeProvider != null) {
        val provider = settings.activeProvider!!
        item {
            SectionCard(
                title = "模型 · " + provider.name,
                subtitle = "价格用于估算费用，可只填其一",
                icon = Icons.Default.Tune
            ) {
                provider.models.forEach { model ->
                    ModelRow(
                        model = model,
                        active = settings.activeModelId == model.id,
                        onSelect = { SettingsRepository.update { it.copy(activeModelId = model.id) } },
                        onDelete = { SettingsRepository.update { s ->
                            s.providers.firstOrNull { it.id == provider.id }?.models?.removeAll { it.id == model.id }
                            if (s.activeModelId == model.id) s.activeModelId = provider.models.firstOrNull()?.id ?: ""
                            s
                        } }
                    )
                }
                Spacer(Modifier.height(8.dp))
                ModelDialogHost(provider = provider, notify = notify)
            }
        }
    }

    item {
        SectionCard(
            title = "翻译参数",
            subtitle = "语言、上下文与行为",
            icon = Icons.Default.Translate
        ) {
            LanguageSettings()
        }
    }

    item {
        SystemPromptCard(notify = notify)
    }

    item {
        GlossaryCard(notify = notify)
    }

    item {
        DictionaryCard(context, notify)
    }
}

// ———————————————————————— 划词词典 ————————————————————————

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryCard(context: Context, notify: Notify) {
    val settings = SettingsRepository.settings
    var showEditor by remember { mutableStateOf(false) }
    var oxfordId by remember(settings.oxfordAppId) { mutableStateOf(settings.oxfordAppId) }
    var oxfordKey by remember(settings.oxfordAppKey) { mutableStateOf(settings.oxfordAppKey) }

    val dictPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { LocalDictionary.importFromUri(context, uri) }
            .onSuccess { notify("已导入本地词典 $it 条，立即生效") }
            .onFailure { notify("导入词典失败：" + (it.message ?: "文件无法读取")) }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = StorageManager.readText(context, uri)
            WordbookRepository.importText(text)
        }.onSuccess { notify("已导入 " + it + " 条词条") }
            .onFailure { notify("导入失败：" + (it.message ?: "文件无法读取")) }
    }

    SectionCard(
        title = "划词词典",
        subtitle = "触摸单词弹出释义，优先查我的词库",
        icon = Icons.Default.AutoAwesome
    ) {
        SwitchRow(
            title = "点词查义",
            subtitle = "在原文（只读时译文也可以）点一下单词即可查释义",
            checked = settings.wordLookupEnabled,
            onCheckedChange = { on -> SettingsRepository.update { it.copy(wordLookupEnabled = on) } }
        )
        Spacer(Modifier.height(8.dp))
        Text("词典来源", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        ChipsRow(
            options = listOf(
                "auto" to "自动",
                "local" to "仅本地",
                "oxford_web" to "牛津网页",
                "oxford_api" to "牛津 API",
                "wiktionary" to "Wiktionary",
                "ai" to "AI 释义"
            ),
            selected = settings.dictionarySource,
            onSelect = { src -> SettingsRepository.update { it.copy(dictionarySource = src) } }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "「自动」会依次尝试：本地词库 → 牛津网页 → 牛津 API → Wiktionary → AI；查不到时自动换下一个来源。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "使用本地词库（离线，最快）",
            subtitle = if (LocalDictionary.isReady) {
                "已加载 " + LocalDictionary.size + " 条" +
                    if (LocalDictionary.hasImport(context)) "（含导入词典）" else ""
            } else {
                "正在后台加载…"
            },
            checked = settings.localDictionaryEnabled,
            onCheckedChange = { on -> SettingsRepository.update { it.copy(localDictionaryEnabled = on) } }
        )
        Spacer(Modifier.height(4.dp))
        Text("释义语言", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        ChipsRow(
            options = listOf("zh" to "中文", "both" to "中英对照", "en" to "英文原版"),
            selected = settings.definitionLanguage,
            onSelect = { lang -> SettingsRepository.update { it.copy(definitionLanguage = lang) } }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "默认「中文」，英文释义会折叠起来，需要时点开即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text("扩展词典", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row {
            OutlinedButton(
                onClick = { dictPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) { Text("导入词典文件") }
            if (LocalDictionary.hasImport(context)) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    LocalDictionary.clearImport(context)
                    notify("已移除导入的词典，恢复内置词库")
                }) { Text("移除") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "支持两种格式：制表符文本（单词/音标/释义）或 ECDICT 的 CSV；" +
                "导入后立即生效，查词不联网。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row {
            OutlinedTextField(
                value = oxfordId,
                onValueChange = {
                    oxfordId = it
                    SettingsRepository.update { s -> s.copy(oxfordAppId = it.trim()) }
                },
                label = { Text("牛津 app_id（可选）") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = oxfordKey,
                onValueChange = {
                    oxfordKey = it
                    SettingsRepository.update { s -> s.copy(oxfordAppKey = it.trim()) }
                },
                label = { Text("app_key（可选）") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            title = "用 AI 补充中文释义",
            subtitle = "词典给出英文释义后，再用当前模型补一行中文",
            checked = settings.dictionaryAiExplain,
            onCheckedChange = { on -> SettingsRepository.update { it.copy(dictionaryAiExplain = on) } }
        )
    }

    SectionCard(
        title = "我的词库",
        subtitle = "共 " + WordbookRepository.count() + " 条 · 查词时优先显示",
        icon = Icons.Default.Star
    ) {
        if (WordbookRepository.words.isEmpty()) {
            Text(
                "还没有词条。查词浮层里点「加入词库」即可收藏，或直接导入文本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            WordbookRepository.words.take(6).forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(entry.term, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.meaning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = {
                        WordbookRepository.remove(entry.term)
                        notify("已删除：" + entry.term)
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (WordbookRepository.count() > 6) {
                Text(
                    "…… 其余 " + (WordbookRepository.count() - 6) + " 条可在词库编辑器里查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Row {
            Button(onClick = { showEditor = true }, modifier = Modifier.weight(1f)) { Text("编辑词库") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { importPicker.launch(arrayOf("text/*", "application/json")) }) {
                Text("导入")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val text = WordbookRepository.exportText()
                if (text.isBlank()) {
                    notify("词库为空")
                    return@OutlinedButton
                }
                val dir = SettingsRepository.settings.storageDirUri
                if (dir.isBlank()) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("词库", text))
                    notify("未设置数据文件夹，已复制词库到剪贴板")
                } else {
                    runCatching {
                        StorageManager.writeTextToFolder(context, dir, "linetrans-wordbook.txt", text)
                    }.onSuccess { notify("已导出到数据文件夹") }
                        .onFailure { notify("导出失败：" + (it.message ?: "未知错误")) }
                }
            }) { Text("导出") }
        }
    }

    if (showEditor) {
        WordbookDialog(
            onDismiss = { showEditor = false },
            onSave = { text ->
                WordbookRepository.clear()
                val count = WordbookRepository.importText(text)
                showEditor = false
                notify("词库已保存，共 " + count + " 条")
            }
        )
    }
}

@Composable
private fun WordbookDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(WordbookRepository.exportText()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑词库") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 8,
                    label = { Text("每行一条：单词=释义，或 单词=音标=释义") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "以 # 开头的行会被忽略；保存会整体覆盖当前词库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = active, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(provider.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                provider.type.label + " · " + provider.baseUrl.ifBlank { "未填写地址" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (provider.apiKey.isBlank()) "未填写 API Key" else "Key: " + provider.apiKey.take(4) + "••••",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { showEdit = true }) {
            Icon(Icons.Default.Edit, contentDescription = "编辑")
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除提供商") },
            text = { Text("确定删除「" + provider.name + "」及其模型配置吗？") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
    if (showEdit) {
        ProviderDialog(
            provider = provider,
            onDismiss = { showEdit = false },
            onConfirm = { name, type, baseUrl, apiKey ->
                provider.name = name
                provider.type = type
                provider.baseUrl = baseUrl
                provider.apiKey = apiKey
                SettingsRepository.save()
                showEdit = false
            }
        )
    }
}

@Composable
private fun ModelRow(
    model: ModelConfig,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var showEdit by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = active, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "输入 " + model.billing.inputPrice + " / 输出 " + model.billing.outputPrice +
                    " 元每百万 token · T=" + model.temperature,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { showEdit = true }) {
            Icon(Icons.Default.Edit, contentDescription = "编辑模型")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除模型", tint = MaterialTheme.colorScheme.error)
        }
    }
    if (showEdit) {
        ModelDialog(
            model = model,
            onDismiss = { showEdit = false },
            onConfirm = { name, input, output, maxTokens, temp, topP, peak, start, end ->
                model.name = name
                model.maxTokens = maxTokens
                model.temperature = temp
                model.topP = topP
                model.billing = BillingConfig(input, output, peak, start, end)
                SettingsRepository.save()
                showEdit = false
            }
        )
    }
}

@Composable
private fun ProviderDialogHost(notify: Notify) {
    var show by remember { mutableStateOf(false) }
    Button(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("添加 API 提供商")
    }
    if (show) {
        ProviderDialog(
            provider = null,
            onDismiss = { show = false },
            onConfirm = { name, type, baseUrl, apiKey ->
                val id = UUID.randomUUID().toString()
                SettingsRepository.update { s ->
                    s.providers.add(ProviderConfig(id, name, type, baseUrl, apiKey))
                    if (s.activeProviderId.isBlank()) s.activeProviderId = id
                    s
                }
                show = false
                notify("已添加提供商 $name")
            }
        )
    }
}

@Composable
private fun ModelDialogHost(provider: ProviderConfig, notify: Notify) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("添加模型")
    }
    if (show) {
        ModelDialog(
            model = null,
            onDismiss = { show = false },
            onConfirm = { name, input, output, maxTokens, temp, topP, peak, start, end ->
                val model = ModelConfig(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    providerId = provider.id,
                    billing = BillingConfig(input, output, peak, start, end),
                    maxTokens = maxTokens,
                    temperature = temp,
                    topP = topP
                )
                SettingsRepository.update { s ->
                    s.providers.firstOrNull { it.id == provider.id }?.models?.add(model)
                    if (s.activeModelId.isBlank()) s.activeModelId = model.id
                    s
                }
                show = false
                notify("已添加模型 $name")
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSettings() {
    val settings = SettingsRepository.settings
    var targetText by remember { mutableStateOf(settings.targetLang) }
    var contextText by remember { mutableStateOf(settings.contextUnits.toString()) }

    SwitchRow(
        title = "自动检测源语言",
        subtitle = "关闭后使用下面选择的源语言",
        checked = settings.detectLanguage,
        onCheckedChange = { on -> SettingsRepository.update { it.copy(detectLanguage = on) } }
    )
    Spacer(Modifier.height(6.dp))
    Text("源语言", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    ChipsRow(
        options = listOf(
            "auto" to "自动", "en" to "English", "zh-CN" to "中文",
            "ja" to "日本語", "ko" to "한국어", "fr" to "Français", "de" to "Deutsch"
        ),
        selected = settings.sourceLang,
        onSelect = { code -> SettingsRepository.update { it.copy(sourceLang = code) } }
    )
    Spacer(Modifier.height(12.dp))
    Text("目标语言", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    ChipsRow(
        options = listOf(
            "zh-CN" to "中文", "en" to "English", "ja" to "日本語",
            "ko" to "한국어", "fr" to "Français", "de" to "Deutsch"
        ),
        selected = targetText,
        onSelect = { code ->
            targetText = code
            SettingsRepository.update { it.copy(targetLang = code) }
        }
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = targetText,
        onValueChange = {
            targetText = it
            SettingsRepository.update { s -> s.copy(targetLang = it) }
        },
        label = { Text("自定义目标语言") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = contextText,
        onValueChange = {
            contextText = it.filter { c -> c.isDigit() }.take(2)
            SettingsRepository.update { s -> s.copy(contextUnits = (contextText.toIntOrNull() ?: 0).coerceIn(0, 10)) }
        },
        label = { Text("前文参考条数（0-10）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    SwitchRow(
        title = "自动跳转下一句",
        subtitle = "AI 翻译完成后自动进入下一句",
        checked = settings.autoAdvance,
        onCheckedChange = { on -> SettingsRepository.update { it.copy(autoAdvance = on) } }
    )
    SwitchRow(
        title = "翻译记忆",
        subtitle = "同一原文已有译文时直接复用，节省 token",
        checked = settings.translationMemory,
        onCheckedChange = { on -> SettingsRepository.update { it.copy(translationMemory = on) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemPromptCard(notify: Notify) {
    val settings = SettingsRepository.settings
    var prompt by remember(settings.systemPrompt) {
        mutableStateOf(settings.systemPrompt.ifBlank { settings.effectivePrompt })
    }
    var showPresets by remember { mutableStateOf(false) }

    SectionCard(
        title = "系统提示词",
        subtitle = "留空使用内置默认；支持占位符变量",
        icon = Icons.Default.AutoAwesome
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前模板：" + (PromptTemplates.byId(settings.promptTemplateId).name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showPresets = !showPresets }) { Text("选择模板") }
        }
        if (showPresets) {
            ChipsRow(
                options = PromptTemplates.all.map { it.id to it.name },
                selected = settings.promptTemplateId,
                onSelect = { id ->
                    val template = PromptTemplates.byId(id)
                    prompt = template.prompt
                    SettingsRepository.update {
                        it.copy(promptTemplateId = id, systemPrompt = template.prompt)
                    }
                    notify("已应用模板：" + template.name)
                }
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            minLines = 4,
            label = { Text("系统提示词") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "可用占位符：{sourceLang} 源语言 · {targetLang} 目标语言 · {docName} 文档名 · " +
                "{mode} 逐行/逐句 · {glossary} 术语表",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row {
            Button(
                onClick = {
                    SettingsRepository.update { it.copy(systemPrompt = prompt.trim()) }
                    notify("已保存系统提示词")
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                prompt = PromptTemplates.DEFAULT
                SettingsRepository.update {
                    it.copy(systemPrompt = PromptTemplates.DEFAULT, promptTemplateId = "default")
                }
                notify("已恢复默认提示词")
            }) { Text("恢复默认") }
        }
    }
}

@Composable
private fun GlossaryCard(notify: Notify) {
    val settings = SettingsRepository.settings
    var text by remember(settings.glossary) { mutableStateOf(settings.glossary) }
    val count = remember(text) { countGlossary(text) }

    SectionCard(
        title = "术语表",
        subtitle = "每行一条「原文=译文」，翻译时强制使用",
        icon = Icons.Default.Check
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            minLines = 4,
            label = { Text("例如：\nApple=苹果\nmachine learning=机器学习") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "已识别 " + count + " 条术语（# 开头的行为注释）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row {
            Button(
                onClick = {
                    SettingsRepository.update { it.copy(glossary = text) }
                    notify("已保存术语表")
                },
                modifier = Modifier.weight(1f)
            ) { Text("保存术语表") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                text = ""
                SettingsRepository.update { it.copy(glossary = "") }
            }) { Text("清空") }
        }
    }
}

private fun countGlossary(text: String): Int = text.lines().count { line ->
    val t = line.trim()
    t.isNotEmpty() && !t.startsWith("#") &&
        t.indexOfFirst { it == '=' || it == '\t' || it == '：' || it == ':' } > 0
}

// ———————————————————————— 界面 ————————————————————————

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.uiTab() {
    item {
        SectionCard(title = "主题", subtitle = "外观与可读性", icon = Icons.Default.Palette) {
            val settings = SettingsRepository.settings
            Text("主题模式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            ChipsRow(
                options = com.linetrans.app.model.ThemeMode.entries.map { it to it.label },
                selected = settings.themeMode,
                onSelect = { mode -> SettingsRepository.update { it.copy(themeMode = mode) } }
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "动态取色（Material You）",
                subtitle = "Android 12+ 跟随系统壁纸配色",
                checked = settings.dynamicColor,
                onCheckedChange = { on -> SettingsRepository.update { it.copy(dynamicColor = on) } }
            )
            Spacer(Modifier.height(4.dp))
            Text("界面文字大小", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            ChipsRow(
                options = listOf(0.85f to "小", 1.0f to "标准", 1.15f to "大", 1.3f to "特大"),
                selected = settings.uiScale,
                onSelect = { scale -> SettingsRepository.update { it.copy(uiScale = scale) } }
            )
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                title = "界面动画",
                subtitle = "页面转场、展开收起等动效",
                checked = settings.animations,
                onCheckedChange = { on -> SettingsRepository.update { it.copy(animations = on) } }
            )
            SwitchRow(
                title = "主界面环形进度",
                subtitle = "在文档卡片上显示进度环",
                checked = settings.showProgressRing,
                onCheckedChange = { on -> SettingsRepository.update { it.copy(showProgressRing = on) } }
            )
        }
    }
    item {
        SectionCard(title = "预览", subtitle = "按当前字号设置渲染", icon = Icons.Default.Translate) {
            Text("原文示例：The quick brown fox jumps over the lazy dog.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text("译文示例：敏捷的棕色狐狸跃过懒狗。", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text("小字说明文本 bodySmall", style = MaterialTheme.typography.bodySmall)
            Text("标签文本 labelMedium", style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ———————————————————————— 数据 ————————————————————————

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.dataTab(context: Context, notify: Notify) {
    val settings = SettingsRepository.settings

    item {
        SectionCard(title = "存储与导出", subtitle = "数据文件夹与默认导出格式", icon = Icons.Default.Storage) {
            DataFolderRow(context, notify)
            Spacer(Modifier.height(10.dp))
            Text("默认导出格式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            ChipsRow(
                options = com.linetrans.app.model.ExportFormat.entries.map { it to it.label },
                selected = settings.defaultExportFormat,
                onSelect = { fmt -> SettingsRepository.update { it.copy(defaultExportFormat = fmt) } }
            )
        }
    }

    item {
        SectionCard(title = "每日目标", subtitle = "用于主界面进度环", icon = Icons.Default.Check) {
            var goalText by remember { mutableStateOf(if (settings.dailyGoal > 0) settings.dailyGoal.toString() else "") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("每日目标（句）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    SettingsRepository.update { it.copy(dailyGoal = goalText.toIntOrNull() ?: 0) }
                    notify("已保存每日目标")
                }) { Text("保存") }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日已完成 " + SettingsRepository.dailyCount() + " 句",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    SettingsRepository.resetDaily()
                    notify("已重置今日进度")
                }) { Text("重置") }
            }
        }
    }

    item {
        UsageCard(notify)
    }

    item {
        BackupCard(context, notify)
    }
}

@Composable
private fun DataFolderRow(context: Context, notify: Notify) {
    val settings = SettingsRepository.settings
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            SettingsRepository.update { it.copy(storageDirUri = uri.toString()) }
            notify("已设置数据文件夹")
        }
    }
    Column {
        Text("数据文件夹", style = MaterialTheme.typography.labelLarge)
        Text(
            if (settings.storageDirUri.isBlank()) "未设置，导出的文件会保存到这里"
            else Uri.decode(settings.storageDirUri),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { picker.launch(null) }, modifier = Modifier.weight(1f)) { Text("选择文件夹") }
            if (settings.storageDirUri.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { SettingsRepository.update { it.copy(storageDirUri = "") } }) {
                    Text("清除")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageCard(notify: Notify) {
    val settings = SettingsRepository.settings
    SectionCard(title = "用量统计", subtitle = "按模型累计的 token 与费用", icon = Icons.Default.AutoAwesome) {
        InfoRow("输入 tokens", SettingsRepository.totalInputTokens.toString())
        InfoRow("输出 tokens", SettingsRepository.totalOutputTokens.toString())
        InfoRow(
            "累计费用",
            "≈ " + String.format(java.util.Locale.CHINA, "%.4f", SettingsRepository.totalCost) + " 元"
        )
        Spacer(Modifier.height(10.dp))
        Text("近 7 天完成量", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        WeekChart()
        if (settings.usageByModel.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("按模型", style = MaterialTheme.typography.labelLarge)
            settings.usageByModel.forEach { record ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(record.modelName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            record.calls.toString() + " 次 · 输入 " + record.inputTokens + " · 输出 " + record.outputTokens,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "≈ " + String.format(java.util.Locale.CHINA, "%.4f", record.cost),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            SettingsRepository.clearUsage()
            notify("已清空用量统计")
        }) { Text("清空统计") }
    }
}

@Composable
private fun WeekChart() {
    val stats = SettingsRepository.recentStats(7)
    val maxUnits = (stats.maxOfOrNull { it.units } ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stats.forEach { stat ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(stat.units.toString(), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Card(
                    Modifier
                        .fillMaxWidth()
                        .height((8 + 72 * stat.units / maxUnits).dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (stat.units > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {}
                Spacer(Modifier.height(4.dp))
                Text(stat.date.takeLast(5), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BackupCard(context: Context, notify: Notify) {
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val json = com.linetrans.app.data.StorageManager.readText(context, uri)
            com.linetrans.app.data.BackupManager.restore(json).getOrThrow()
        }.onSuccess { result ->
            notify(
                "已恢复 " + result.docs + " 篇文档" +
                    if (result.settingsRestored) "（含设置）" else ""
            )
        }
            .onFailure { notify("恢复失败：" + (it.message ?: "文件格式不正确")) }
    }

    SectionCard(title = "备份与恢复", subtitle = "包含全部文档与设置", icon = Icons.Default.Save) {
        Row {
            Button(
                onClick = {
                    runCatching {
                        com.linetrans.app.data.BackupManager.exportToFolder(context)
                    }.onSuccess { name ->
                        notify("备份已保存到数据文件夹：$name")
                    }.onFailure { notify("备份失败：" + (it.message ?: "未知错误")) }
                },
                modifier = Modifier.weight(1f)
            ) { Text("导出备份") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/json", "text/*")) }) {
                Text("恢复备份")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "当前共 " + DocRepository.docs.size + " 篇文档、" +
                DocRepository.docs.sumOf { it.totalCount } + " 个句子。恢复为合并模式，不会覆盖已有文档。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ———————————————————————— 高级 ————————————————————————

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.advancedTab(context: Context, notify: Notify) {
    item {
        SectionCard(title = "网络与请求", subtitle = "超时、重试与代理", icon = Icons.Default.Sync) {
            NetworkSettings(context, notify)
        }
    }

    item {
        SectionCard(title = "编辑与输入", subtitle = "自动保存与手势", icon = Icons.Default.Edit) {
            val settings = SettingsRepository.settings
            var saveText by remember { mutableStateOf(settings.autoSaveMs.toString()) }
            OutlinedTextField(
                value = saveText,
                onValueChange = {
                    saveText = it.filter { c -> c.isDigit() }.take(4)
                    SettingsRepository.update { s ->
                        s.copy(autoSaveMs = (saveText.toIntOrNull() ?: 700).coerceIn(200, 5000))
                    }
                },
                label = { Text("自动保存延迟（毫秒，200-5000）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "左右滑动切换上下句",
                subtitle = "在翻译页顶部区域左右滑动",
                checked = settings.swipeToSwitch,
                onCheckedChange = { on -> SettingsRepository.update { it.copy(swipeToSwitch = on) } }
            )
            SwitchRow(
                title = "翻译时保持屏幕常亮",
                checked = settings.keepScreenOn,
                onCheckedChange = { on -> SettingsRepository.update { it.copy(keepScreenOn = on) } }
            )
        }
    }

    item {
        SectionCard(
            title = "局域网 Web 服务",
            subtitle = "同一局域网内的设备用浏览器打开即可继续翻译",
            icon = Icons.Default.Storage
        ) {
            WebServerSettings(context, notify)
        }
    }
}

@Composable
private fun NetworkSettings(context: Context, notify: Notify) {
    val settings = SettingsRepository.settings
    var timeoutText by remember { mutableStateOf(settings.requestTimeoutSec.toString()) }
    var retryText by remember { mutableStateOf(settings.maxRetries.toString()) }
    var proxyText by remember { mutableStateOf(settings.proxyUrl) }
    var uaText by remember { mutableStateOf(settings.userAgent) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    OutlinedTextField(
        value = timeoutText,
        onValueChange = {
            timeoutText = it.filter { c -> c.isDigit() }.take(3)
            SettingsRepository.update { s -> s.copy(requestTimeoutSec = (timeoutText.toIntOrNull() ?: 120).coerceIn(10, 600)) }
        },
        label = { Text("请求超时（秒）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = retryText,
        onValueChange = {
            retryText = it.filter { c -> c.isDigit() }.take(1)
            SettingsRepository.update { s -> s.copy(maxRetries = (retryText.toIntOrNull() ?: 2).coerceIn(0, 5)) }
        },
        label = { Text("失败重试次数（0-5）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = proxyText,
        onValueChange = {
            proxyText = it
            SettingsRepository.update { s -> s.copy(proxyUrl = it.trim()) }
        },
        label = { Text("HTTP 代理（可留空）") },
        placeholder = { Text("http://127.0.0.1:7890") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = uaText,
        onValueChange = {
            uaText = it
            SettingsRepository.update { s -> s.copy(userAgent = it.trim()) }
        },
        label = { Text("自定义 User-Agent（可留空）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = {
            testing = true
            val current = SettingsRepository.settings
            scope.launch {
                val result = com.linetrans.app.ai.TranslationService(context).testConnection(current)
                testing = false
                notify(result.message)
            }
        },
        enabled = !testing,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.width(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (testing) "测试中…" else "测试当前模型连接")
    }
}

@Composable
private fun WebServerSettings(context: Context, notify: Notify) {
    val settings = SettingsRepository.settings
    var portText by remember { mutableStateOf(settings.webServerPort.toString()) }
    var tokenText by remember { mutableStateOf(settings.webServerToken) }
    val running = com.linetrans.app.server.WebServerService.isRunning

    SwitchRow(
        title = "启用局域网 Web 服务",
        subtitle = if (running) "运行中：浏览器打开首页即可继续翻译" else "已停止",
        checked = settings.webServerEnabled,
        onCheckedChange = { checked ->
            if (checked) {
                SettingsRepository.update { it.copy(webServerEnabled = true) }
                com.linetrans.app.server.WebServerService.start(context)
                notify("局域网 Web 服务已开启")
            } else {
                com.linetrans.app.server.WebServerService.stop(context)
                SettingsRepository.update { it.copy(webServerEnabled = false) }
                notify("局域网 Web 服务已关闭")
            }
        }
    )
    SwitchRow(
        title = "应用启动后自动开启",
        checked = settings.webServerAutoStart,
        onCheckedChange = { on -> SettingsRepository.update { it.copy(webServerAutoStart = on) } }
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val p = (portText.toIntOrNull() ?: 8080).coerceIn(1024, 65535)
            portText = p.toString()
            SettingsRepository.update { it.copy(webServerPort = p) }
            if (com.linetrans.app.server.WebServerService.isRunning) {
                com.linetrans.app.server.WebServerService.stop(context)
                com.linetrans.app.server.WebServerService.start(context)
            }
            notify("端口已设为 $p")
        }) { Text("应用") }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = tokenText,
        onValueChange = {
            tokenText = it
            SettingsRepository.update { s -> s.copy(webServerToken = it.trim()) }
        },
        label = { Text("访问令牌（可留空）") },
        placeholder = { Text("填写后访问需要携带 ?token=xxx") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (settings.webServerEnabled || running) {
        val ip = com.linetrans.app.util.NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
        val suffix = if (settings.webServerToken.isBlank()) "" else "?token=" + settings.webServerToken
        val base = "http://" + ip + ":" + settings.webServerPort
        val url = base + "/" + suffix
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                notify("已复制访问地址")
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制地址")
            }
        }
        Text(
            "网页翻译台：" + base + "/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ———————————————————————— 关于 ————————————————————————

private fun androidx.compose.foundation.lazy.LazyListScope.aboutTab(context: Context) {
    item {
        SectionCard(
            title = "逐行翻译 · 安卓客户端",
            subtitle = "v" + com.linetrans.app.BuildConfig.VERSION_NAME,
            icon = Icons.Default.Translate
        ) {
            Text(
                "一款用于逐行 / 逐句对照翻译的安卓原生应用，支持多家 AI 接口、翻译记忆、" +
                    "批量翻译，并内置局域网网页翻译台。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bityng/line-trans-android"))
                    )
                }
            }) { Text("安卓客户端仓库") }
        }
    }
    item {
        SectionCard(title = "网页服务端", subtitle = "line-trans-web", icon = Icons.Default.Computer) {
            Text(
                "本客户端内置的网页翻译台，与独立服务端 line-trans-web 共用同一套界面与 API：" +
                    "在手机开启「局域网 Web 服务」后，同一局域网的电脑浏览器打开手机地址即可继续翻译；" +
                    "也可以在电脑 / NAS 上单独运行 Web 服务端使用。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bityng/line-trans-web"))
                    )
                }
            }) { Text("网页服务端仓库") }
        }
    }
    item {
        SectionCard(title = "最近更新", subtitle = "v1.8.1", icon = Icons.Default.AutoAwesome) {
            listOf(
                "内置网页台补齐查词接口：局域网浏览器里也能点词查义了",
                "移除网页台页脚的 Shell 终端死链",
                "网页台新增划词查义：点词即出释义，复用内置离线词库，不联网也能查",
                "修复词形还原整列失效：可用词形映射从 0 恢复到 101909 条",
                "移除网页 shell 终端，局域网服务专注网页翻译台",
                "项目改用 AGPL-3.0 许可，并在「关于」里保留源码入口",
                "工程目录整理为「安卓端 / 网页端」，新增面向后续开发的《项目说明与开发指南》",
                "修复长文字撑破布局：文件夹名/句子序号过长时不再把按钮挤出卡片（改为自动换行与省略号）",
                "查看模式重做：对照/仅原文/仅译文切换，底部翻页与朗读/复制按钮，切分方式只读展示",
                "内置网页台左上角改用应用图标",
                "逐句切分重写：支持缩写（Mr. / U.S. / e.g.）、小数点、网址、首字母缩写，省略号不再拆碎",
                "段落内的排版换行会自动合并成一句（英文补空格、中日韩不补），空行才分段",
                "网页端：长文档分窗口渲染（每批 60 句，滚动自动加载），上千句也不卡",
                "网页端：新增进度环、句号跳转、按文件夹分组的文档列表、手机端抽屉侧栏",
                "网页端：原文/译文分区显示，双击原文可直接编辑",
                "新增内置离线词库（4 万常用词 + 词形还原），点词秒出中文释义，不再依赖联网",
                "释义默认中文，英文释义折叠起来；可切换中英对照 / 英文原版",
                "支持导入更大的词典文件（ECDICT CSV 或制表符文本），导入后立即生效",
                "修复朗读没有声音：等待语音引擎初始化并按语言自动切换",
                "网页端修复设置弹窗一直显示等 bug，并换了新的应用图标（favicon / 主屏图标）",
                "划词查义：点单词在词上方浮出释义，优先查「我的词库」",
                "词典来源可选牛津网页 / 牛津 API / Wiktionary / AI，并可自动回退",
                "我的词库：一键收藏释义、编辑器批量维护、导入导出",
                "局域网 Web 服务改为网页翻译台：浏览器里继续翻译、AI 翻译与导出",
                "新增只读「查看」模式，并记住每篇文档的阅读位置",
                "修复切换逐行/逐句时跳回第一句的问题",
                "键盘弹出时自动收起次要区域，译文输入框不再被挤压遮挡",
                "页面转场与展开动画统一曲线与时长，过渡更顺滑",
                "设置页重构为 AI / 界面 / 数据 / 高级 / 关于 五个分类",
                "系统提示词与术语表开放给用户自定义",
                "翻译记忆、撤销重做、查找替换、朗读、收藏",
                "导出支持 Markdown / CSV / JSON，支持分享",
                "用量统计与近 7 天进度图表、文档置顶排序与备份恢复"
            ).forEach { line ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("· ", style = MaterialTheme.typography.bodyMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    item {
        SectionCard(title = "开源许可", subtitle = "AGPL-3.0-or-later", icon = Icons.Default.Check) {
            Text(
                "本项目按 GNU Affero GPL v3.0 开源。AGPL 对网络服务有额外要求：" +
                    "把本项目或其修改版部署成网络服务供他人使用时，需要向使用者提供对应源码，" +
                    "所以上面的两个仓库入口请保留。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
