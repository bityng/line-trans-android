package com.linetrans.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.linetrans.app.ai.DictionaryService
import com.linetrans.app.ai.TranslationService
import com.linetrans.app.data.DocRepository
import com.linetrans.app.data.ExportManager
import com.linetrans.app.data.SettingsRepository
import com.linetrans.app.data.WordbookRepository
import com.linetrans.app.model.AppSettings
import com.linetrans.app.model.ExportFormat
import com.linetrans.app.model.TranslationDoc
import com.linetrans.app.model.TranslationUnit
import com.linetrans.app.model.UnitMode
import com.linetrans.app.util.CostCalculator
import com.linetrans.app.util.TextParser
import com.linetrans.app.util.SpeechService
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private data class EditSnapshot(val index: Int, val text: String, val done: Boolean)

private enum class JumpFilter(val label: String) {
    ALL("全部"),
    TODO("未完成"),
    STARRED("收藏")
}

private enum class ReplaceScope(val label: String) {
    SOURCE("原文"),
    TRANSLATION("译文"),
    BOTH("原文+译文")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranslationScreen(docId: String, startIndex: Int = 0, viewOnly: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { TranslationService(context) }
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current
    val view = LocalView.current

    val doc = DocRepository.get(docId) ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (doc.units.isEmpty()) {
        EmptyDocScreen(doc = doc, onBack = onBack)
        return
    }

    val settings = SettingsRepository.settings
    var revision by remember(docId) { mutableIntStateOf(0) }
    var currentIndex by remember(docId) {
        mutableIntStateOf(startIndex.coerceIn(0, (doc.units.size - 1).coerceAtLeast(0)))
    }
    var mode by remember(docId) { mutableStateOf(doc.unitMode) }
    var readOnly by remember(docId) { mutableStateOf(viewOnly) }
    var translatedText by remember(docId) { mutableStateOf(doc.units[currentIndex].translation) }
    var editableOriginal by remember(docId) { mutableStateOf(false) }
    // 分割线的锁定状态与比例是持久化设置：打开界面时读回，改动时写回
    var dividerLocked by remember(docId) { mutableStateOf(SettingsRepository.settings.dividerLocked) }
    var splitFraction by remember(docId) {
        mutableFloatStateOf(
            SettingsRepository.settings.splitFraction.coerceIn(
                AppSettings.MIN_SPLIT_FRACTION,
                AppSettings.MAX_SPLIT_FRACTION
            )
        )
    }
    var aiLoading by remember(docId) { mutableStateOf(false) }
    var topMenu by remember(docId) { mutableStateOf(false) }
    var unitMenu by remember(docId) { mutableStateOf(false) }
    var showDone by remember(docId) { mutableStateOf(false) }
    var showJump by remember(docId) { mutableStateOf(false) }
    var jumpFilter by remember(docId) { mutableStateOf(JumpFilter.ALL) }
    var showBatchConfirm by remember(docId) { mutableStateOf(false) }
    var batchRunning by remember(docId) { mutableStateOf(false) }
    var batchDone by remember(docId) { mutableIntStateOf(0) }
    var batchTotal by remember(docId) { mutableIntStateOf(0) }
    var cancelBatch by remember(docId) { mutableStateOf(false) }
    var usage by remember(docId) { mutableStateOf(TranslationService.UsageState()) }
    var memoryHits by remember(docId) { mutableIntStateOf(0) }
    var showFindReplace by remember(docId) { mutableStateOf(false) }
    var editSessionIndex by remember(docId) { mutableIntStateOf(-1) }
    var lookupWord by remember(docId) { mutableStateOf<String?>(null) }
    var lookupAnchor by remember(docId) { mutableStateOf(IntOffset.Zero) }
    var lookupEntry by remember(docId) { mutableStateOf<DictionaryService.Entry?>(null) }
    var lookupLoading by remember(docId) { mutableStateOf(false) }
    /** 查看模式的显示方式：0 对照 / 1 仅原文 / 2 仅译文 */
    var readView by remember(docId) { mutableIntStateOf(0) }

    val undoStack = remember(docId) { mutableStateListOf<EditSnapshot>() }
    val redoStack = remember(docId) { mutableStateListOf<EditSnapshot>() }

    // 朗读引擎在应用启动时初始化，这里只做一次保险
    LaunchedEffect(Unit) { SpeechService.init(context) }

    // 屏幕常亮（可选）
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = SettingsRepository.settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val stats = remember(revision) {
        Triple(doc.translatedCount, doc.totalCount, doc.progress)
    }
    val animatedProgress by animateFloatAsState(stats.third, animationSpec = Motion.value(520), label = "progress")

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun currentUnit(): TranslationUnit? = doc.units.getOrNull(currentIndex)

    fun pushSnapshot(index: Int = currentIndex, force: Boolean = false) {
        if (!force && editSessionIndex == index) return
        val unit = doc.units.getOrNull(index) ?: return
        undoStack.add(EditSnapshot(index, unit.translation, unit.done))
        while (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
        editSessionIndex = index
    }

    /** 统一的单元格更新入口：处理“今日进度”与文档保存。 */
    fun applyUnit(unit: TranslationUnit, translation: String? = null, done: Boolean? = null) {
        val wasDone = unit.isDone
        if (translation != null) unit.translation = translation
        if (done != null) unit.done = done
        if (!wasDone && unit.isDone) SettingsRepository.bumpDaily(1)
        DocRepository.save(doc)
        revision++
    }

    fun select(index: Int) {
        if (index !in doc.units.indices) return
        currentUnit()?.let { unit ->
            if (unit.translation != translatedText) applyUnit(unit, translation = translatedText)
        }
        currentIndex = index
        // 记住阅读位置，下次打开文档时回到这里
        doc.lastIndex = index
        DocRepository.save(doc)
        translatedText = doc.units[index].translation
        editableOriginal = false
        editSessionIndex = -1
    }

    fun goNext() {
        val unit = currentUnit() ?: return
        if (!readOnly) applyUnit(unit, translation = translatedText, done = true)
        if (currentIndex < doc.units.size - 1) select(currentIndex + 1) else showDone = true
    }

    fun goPrev() {
        if (currentIndex <= 0) return
        select(currentIndex - 1)
    }

    fun skipCurrent() {
        val unit = currentUnit() ?: return
        pushSnapshot(force = true)
        applyUnit(unit, translation = "", done = true)
        translatedText = ""
        if (currentIndex < doc.units.size - 1) select(currentIndex + 1) else showDone = true
    }

    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        val unit = doc.units.getOrNull(snap.index) ?: return
        redoStack.add(EditSnapshot(snap.index, unit.translation, unit.done))
        unit.translation = snap.text
        unit.done = snap.done
        DocRepository.save(doc)
        revision++
        if (snap.index != currentIndex) select(snap.index) else translatedText = snap.text
        editSessionIndex = -1
        toast("已撤销")
    }

    fun redo() {
        val snap = redoStack.removeLastOrNull() ?: return
        val unit = doc.units.getOrNull(snap.index) ?: return
        undoStack.add(EditSnapshot(snap.index, unit.translation, unit.done))
        unit.translation = snap.text
        unit.done = snap.done
        DocRepository.save(doc)
        revision++
        if (snap.index != currentIndex) select(snap.index) else translatedText = snap.text
        editSessionIndex = -1
        toast("已重做")
    }

    fun speak(text: String) {
        if (text.isBlank()) {
            notify("没有可朗读的内容")
            return
        }
        SpeechService.speak(text).onFailure { notify(it.message ?: "朗读失败") }
    }

    fun copyToClipboard(text: String, label: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("已复制")
    }

    fun shareText(text: String, subject: String) {
        if (text.isBlank()) {
            notify("没有可分享的内容")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "分享")) }
            .onFailure { notify("没有可用的分享应用") }
    }

    fun countCost(result: TranslationService.Result) {
        val model = SettingsRepository.settings.activeModel
        val cost = if (model != null) CostCalculator.costFor(model, result.promptTokens, result.completionTokens) else 0.0
        usage = usage.plus(result, cost)
    }

    fun switchMode(newMode: UnitMode) {
        if (newMode == doc.unitMode) return
        // 锚定当前这句的原文，切分方式变化后仍停在原来的位置
        val anchor = currentUnit()?.source
        val anchorTranslated = currentUnit()?.translation ?: ""
        val source = if (doc.sourceText.isNotBlank()) doc.sourceText else doc.units.joinToString("\n") { it.source }
        val oldBySource = doc.units.associateBy { it.source }
        val newUnits = TextParser.parse(source, newMode).map { u ->
            val old = oldBySource[u.source]
            if (old != null) TranslationUnit(u.source, old.translation, old.done, old.starred) else u
        }.toMutableList()
        doc.units.clear()
        doc.units.addAll(newUnits)
        doc.unitMode = newMode
        mode = newMode
        val restored = when {
            anchor.isNullOrBlank() -> -1
            else -> doc.units.indexOfFirst { it.source == anchor }
        }
        currentIndex = if (restored >= 0) restored else {
            // 逐句拆开后原句不在第一段时，退一步找包含关系
            if (anchor.isNullOrBlank()) 0
            else doc.units.indexOfFirst { it.source.contains(anchor) || anchor.contains(it.source) }.coerceAtLeast(0)
        }
        doc.lastIndex = currentIndex
        translatedText = doc.units.getOrNull(currentIndex)?.translation ?: anchorTranslated
        undoStack.clear()
        redoStack.clear()
        DocRepository.save(doc, immediate = true)
        revision++
        notify("已切换为" + (if (newMode == UnitMode.SENTENCE) "逐句" else "逐行") + "模式")
    }

    /** 点词查义：先给缓存，再异步请求词典。 */
    fun lookupAt(word: String, anchor: IntOffset) {
        if (!SettingsRepository.settings.wordLookupEnabled) return
        if (word.isBlank() || word.none { it.isLetter() }) return
        lookupWord = word
        lookupAnchor = anchor
        val cached = DictionaryService.cached(word)
        lookupEntry = cached
        lookupLoading = cached == null
        if (cached != null) return
        val requested = word
        scope.launch {
            val entry = DictionaryService.lookup(SettingsRepository.settings, requested, service)
            if (lookupWord == requested) {
                lookupEntry = entry
                lookupLoading = false
            }
        }
    }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { notify("无法打开浏览器") }
    }

    fun runAi(onFinished: (() -> Unit)? = null) {
        val unit = currentUnit() ?: return
        val current = SettingsRepository.settings
        if (current.activeProvider == null) {
            notify("请先在设置中添加并选择 API 提供商")
            return
        }
        if (unit.source.isBlank()) {
            notify("原文为空，无法翻译")
            return
        }
        // 翻译记忆：相同原文已有译文时直接复用，省一次请求
        if (SettingsRepository.settings.translationMemory) {
            val memory = doc.memoryTranslation(unit.source, currentIndex)
            if (memory != null && unit.translation.isBlank()) {
                pushSnapshot(force = true)
                translatedText = memory
                applyUnit(unit, translation = memory, done = true)
                memoryHits++
                notify("命中翻译记忆，已复用已有译文")
                onFinished?.invoke()
                return
            }
        }
        aiLoading = true
        pushSnapshot(force = true)
        val index = currentIndex
        scope.launch {
            try {
                val result = service.translate(
                    settings = current,
                    docName = doc.name,
                    unit = unit,
                    mode = mode,
                    index = index,
                    total = doc.units.size,
                    previousUnits = doc.units.take(index)
                )
                translatedText = result.text
                applyUnit(unit, translation = result.text, done = true)
                countCost(result)
                onFinished?.invoke()
            } catch (e: Exception) {
                notify("翻译失败：" + (e.message ?: "未知错误"))
            } finally {
                aiLoading = false
            }
        }
    }

    fun advanceAfterAi() {
        val next = doc.nextUndoneIndex(currentIndex + 1)
        when {
            next != null -> select(next)
            currentIndex < doc.units.size - 1 -> select(currentIndex + 1)
            else -> showDone = true
        }
    }

    fun runBatch() {
        if (SettingsRepository.settings.activeProvider == null) {
            notify("请先在设置中添加并选择 API 提供商")
            return
        }
        batchRunning = true
        cancelBatch = false
        batchDone = 0
        batchTotal = doc.remainingCount
        val total = doc.units.size
        scope.launch {
            var failed: String? = null
            for (i in doc.units.indices) {
                if (cancelBatch) break
                val unit = doc.units[i]
                if (unit.isDone) continue
                val memory = if (SettingsRepository.settings.translationMemory) {
                    doc.memoryTranslation(unit.source, i)
                } else null
                if (memory != null) {
                    unit.translation = memory
                    unit.done = true
                    memoryHits++
                    SettingsRepository.bumpDaily(1)
                    DocRepository.save(doc)
                    batchDone++
                    revision++
                    continue
                }
                try {
                    val result = service.translate(
                        settings = SettingsRepository.settings,
                        docName = doc.name,
                        unit = unit,
                        mode = mode,
                        index = i,
                        total = total,
                        previousUnits = doc.units.take(i)
                    )
                    unit.translation = result.text
                    unit.done = true
                    countCost(result)
                    SettingsRepository.bumpDaily(1)
                    DocRepository.save(doc)
                    batchDone++
                    revision++
                    currentIndex = i
                    translatedText = result.text
                } catch (e: Exception) {
                    failed = e.message ?: "未知错误"
                    break
                }
            }
            batchRunning = false
            DocRepository.save(doc, immediate = true)
            when {
                failed != null -> notify("批量翻译中断：" + failed)
                cancelBatch -> notify("已停止批量翻译")
                else -> notify("批量翻译完成，共 " + batchDone + " " + unitLabel(mode))
            }
        }
    }

    fun exit() {
        currentUnit()?.let { unit ->
            if (unit.translation != translatedText) applyUnit(unit, translation = translatedText)
        }
        DocRepository.flushAll()
        onBack()
    }

    BackHandler { exit() }

    val swipeModifier = Modifier.pointerInput(settings.swipeToSwitch, currentIndex) {
        if (!SettingsRepository.settings.swipeToSwitch) return@pointerInput
        val threshold = with(density) { 64.dp.toPx() }
        var accumulated = 0f
        detectHorizontalDragGestures(
            onDragStart = { accumulated = 0f },
            onDragEnd = {
                when {
                    accumulated > threshold -> goPrev()
                    accumulated < -threshold -> goNext()
                }
                accumulated = 0f
            },
            onHorizontalDrag = { _, delta -> accumulated += delta }
        )
    }

    // 键盘弹出时收起次要区域（模式切换 / 分割线 / 用量条），把高度让给译文输入框
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    var contentHeightPx by remember(docId) { mutableIntStateOf(0) }
    val imeInsetsPx = WindowInsets.ime.getBottom(density)
    val imeOpen = remember(contentHeightPx, screenHeightDp, imeInsetsPx) {
        val screenPx = with(density) { screenHeightDp.dp.toPx() }
        val threshold = with(density) { 240.dp.toPx() }
        imeInsetsPx > threshold / 2 || (contentHeightPx > 0 && contentHeightPx < screenPx - threshold)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = {
                    Column {
                        Text(
                            doc.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "已完成 " + stats.first + " / " + stats.second + " " + unitLabel(mode) +
                                " · " + if (mode == UnitMode.SENTENCE) "逐句" else "逐行",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { exit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (readOnly) {
                        TextButton(onClick = {
                            readOnly = false
                            notify("已切换到编辑模式")
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("开始编辑")
                        }
                    } else {
                        IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销")
                        }
                        IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "重做")
                        }
                    }
                    Box {
                        IconButton(onClick = { topMenu = true }, enabled = !batchRunning) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("跳转到…") },
                                onClick = { topMenu = false; showJump = true }
                            )
                            if (!readOnly) {
                                DropdownMenuItem(
                                    text = { Text("查找与替换…") },
                                    onClick = { topMenu = false; showFindReplace = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("批量翻译剩余 " + doc.remainingCount + " " + unitLabel(mode)) },
                                    onClick = { topMenu = false; showBatchConfirm = true }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("跳到下一个未完成") },
                                onClick = {
                                    topMenu = false
                                    val next = doc.nextUndoneIndex(currentIndex)
                                    if (next == null) notify("已全部完成") else select(next)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("复制全部译文") },
                                onClick = {
                                    topMenu = false
                                    val all = doc.units.map { it.translation }.filter { it.isNotBlank() }
                                    if (all.isEmpty()) notify("还没有译文") else copyToClipboard(all.joinToString("\n"), "译文")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("分享全文") },
                                onClick = {
                                    topMenu = false
                                    shareText(ExportManager.shareText(doc), doc.name)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .onSizeChanged { contentHeightPx = it.height }
                .padding(horizontal = 16.dp)
                .padding(bottom = if (imeOpen) 4.dp else 8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter) {
                        goNext()
                        true
                    } else false
                }
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().padding(top = if (imeOpen) 4.dp else 10.dp).height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (imeOpen) 4.dp else 8.dp)
                    .then(swipeModifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "第 " + (currentIndex + 1) + " / " + doc.units.size + " " + unitLabel(mode),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Text(
                        (stats.third * 100).roundToInt().toString() + "%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                if (readOnly) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                        Text(
                            "只读预览",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { goPrev() }, enabled = currentIndex > 0) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一句")
                }
                FilledTonalButton(onClick = { goNext() }, enabled = !aiLoading && !batchRunning) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (currentIndex == doc.units.size - 1) "完成" else "下一句")
                }
            }

            if (!imeOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (readOnly) {
                        // 查看模式：切分方式只展示不可改，改为切换显示内容
                        Text(
                            if (mode == UnitMode.SENTENCE) "逐句" else "逐行",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        FilterChip(
                            selected = readView == 0,
                            onClick = { readView = 0 },
                            label = { Text("对照") }
                        )
                        Spacer(Modifier.width(6.dp))
                        FilterChip(
                            selected = readView == 1,
                            onClick = { readView = 1 },
                            label = { Text("原文") }
                        )
                        Spacer(Modifier.width(6.dp))
                        FilterChip(
                            selected = readView == 2,
                            onClick = { readView = 2 },
                            label = { Text("译文") }
                        )
                    } else {
                        FilterChip(
                            selected = mode == UnitMode.SENTENCE,
                            onClick = { switchMode(UnitMode.SENTENCE) },
                            label = { Text("逐句") }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = mode == UnitMode.LINE,
                            onClick = { switchMode(UnitMode.LINE) },
                            label = { Text("逐行") }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        currentUnit()?.let { unit ->
                            pushSnapshot(force = true)
                            unit.starred = !unit.starred
                            DocRepository.save(doc)
                            revision++
                        }
                    }) {
                        Icon(
                            if (currentUnit()?.starred == true) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "收藏本句",
                            tint = if (currentUnit()?.starred == true) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = { showJump = true }) {
                        Icon(Icons.Default.Search, contentDescription = "跳转列表")
                    }
                }
            }

            val showSource = !readOnly || readView != 2
            val showTranslation = !readOnly || readView != 1

            // 原文
            if (showSource) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            imeOpen -> Modifier.height(128.dp)
                            readOnly && !showTranslation -> Modifier.weight(1f)
                            else -> Modifier.weight(splitFraction)
                        }
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val lookupOn = SettingsRepository.settings.wordLookupEnabled
                    Text(
                        when {
                            readOnly && lookupOn -> "原文（只读 · 点词查义）"
                            readOnly -> "原文（只读）"
                            editableOriginal -> "原文（编辑中）"
                            lookupOn -> "原文（点词查义）"
                            else -> "原文"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { speak(currentUnit()?.source ?: "") }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "朗读原文", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { copyToClipboard(currentUnit()?.source ?: "", "原文") }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制原文", modifier = Modifier.size(20.dp))
                    }
                    if (!readOnly) {
                        IconButton(onClick = { editableOriginal = !editableOriginal }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = if (editableOriginal) "完成修改" else "修改原文",
                                modifier = Modifier.size(20.dp),
                                tint = if (editableOriginal) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                val sourceText = currentUnit()?.source ?: ""
                if (editableOriginal && !readOnly) {
                    OutlinedTextField(
                        value = sourceText,
                        onValueChange = { text ->
                            currentUnit()?.let { unit ->
                                unit.source = text
                                DocRepository.save(doc)
                                revision++
                            }
                        },
                        placeholder = { Text("原文") },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = RoundedCornerShape(14.dp)
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            WordLookupText(
                                text = sourceText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                                onWordTap = { word, anchor -> lookupAt(word, anchor) }
                            )
                        }
                    }
                }
            }
            }

            if (!imeOpen && !readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = {
                        val next = !dividerLocked
                        dividerLocked = next
                        SettingsRepository.update { it.copy(dividerLocked = next) }
                        toast(if (next) "已锁定分割线" else "已解锁分割线")
                    }) {
                        Icon(
                            if (dividerLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (dividerLocked) "解锁分割线" else "锁定分割线",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Slider(
                        value = splitFraction,
                        onValueChange = {
                            if (!dividerLocked) {
                                val v = it.coerceIn(
                                    AppSettings.MIN_SPLIT_FRACTION,
                                    AppSettings.MAX_SPLIT_FRACTION
                                )
                                splitFraction = v
                                SettingsRepository.update { s -> s.copy(splitFraction = v) }
                            }
                        },
                        valueRange = AppSettings.MIN_SPLIT_FRACTION..AppSettings.MAX_SPLIT_FRACTION,
                        enabled = !dividerLocked,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 译文
            if (showTranslation) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            imeOpen -> Modifier.weight(1f)
                            readOnly && !showSource -> Modifier.weight(1f)
                            else -> Modifier.weight(1f - splitFraction)
                        }
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("译文", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    if (imeOpen && !readOnly) {
                        FilledTonalButton(
                            onClick = {
                                runAi(onFinished = {
                                    if (SettingsRepository.settings.autoAdvance) advanceAfterAi()
                                })
                            },
                            enabled = !aiLoading && !batchRunning,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (aiLoading) "翻译中…" else "AI 翻译", style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = { speak(translatedText) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "朗读译文", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { copyToClipboard(translatedText, "译文") }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制译文", modifier = Modifier.size(20.dp))
                    }
                    Box {
                        IconButton(onClick = { unitMenu = true }, enabled = !batchRunning) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                            if (!readOnly) {
                                DropdownMenuItem(
                                    text = { Text("粘贴原文") },
                                    onClick = {
                                        unitMenu = false
                                        pushSnapshot(force = true)
                                        val text = currentUnit()?.source ?: ""
                                        translatedText = text
                                        currentUnit()?.let { applyUnit(it, translation = text) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("清空译文") },
                                    onClick = {
                                        unitMenu = false
                                        pushSnapshot(force = true)
                                        translatedText = ""
                                        currentUnit()?.let { applyUnit(it, translation = "", done = false) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("重新翻译本句") },
                                    onClick = { unitMenu = false; runAi() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("分享本句") },
                                onClick = {
                                    unitMenu = false
                                    shareText(translatedText, doc.name)
                                }
                            )
                        }
                    }
                }
                if (!imeOpen && !readOnly) {
                    Button(
                        onClick = {
                            runAi(onFinished = {
                                if (SettingsRepository.settings.autoAdvance) advanceAfterAi()
                            })
                        },
                        enabled = !aiLoading && !batchRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AnimatedContent(
                            targetState = aiLoading,
                            transitionSpec = {
                                fadeIn(animationSpec = Motion.enter(220)) togetherWith
                                    fadeOut(animationSpec = Motion.exit(160))
                            },
                            label = "ai-button"
                        ) { loading ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (loading) "翻译中…" else "AI 翻译")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (readOnly) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            WordLookupText(
                                text = translatedText.ifBlank { "（未翻译）" },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                                onWordTap = { word, anchor -> lookupAt(word, anchor) }
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = translatedText,
                        onValueChange = { text ->
                            pushSnapshot()
                            translatedText = text
                            currentUnit()?.let { applyUnit(it, translation = text) }
                        },
                        placeholder = { Text("在这里输入译文") },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
            }

            // 查看模式的底部操作条：翻页 / 朗读 / 复制对照，放在拇指更容易够到的位置
            if (readOnly) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { goPrev() },
                        enabled = currentIndex > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上一句")
                    }
                    IconButton(onClick = { speak(currentUnit()?.source ?: "") }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "朗读原文")
                    }
                    IconButton(onClick = {
                        val source = currentUnit()?.source ?: ""
                        val target = translatedText
                        copyToClipboard(if (target.isBlank()) source else source + "\n" + target, "对照")
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制对照")
                    }
                    Button(
                        onClick = { goNext() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (currentIndex == doc.units.size - 1) "完成" else "下一句")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (!imeOpen && !readOnly) {
                UsageBar(
                    usage = usage,
                    memoryHits = memoryHits,
                    batchRunning = batchRunning,
                    batchDone = batchDone,
                    batchTotal = batchTotal,
                    modifier = swipeModifier
                )
            }
        }
    }

    if (showJump) {
        JumpDialog(
            doc = doc,
            currentIndex = currentIndex,
            filter = jumpFilter,
            onFilterChange = { jumpFilter = it },
            onDismiss = { showJump = false },
            onSelect = { showJump = false; select(it) }
        )
    }

    if (showFindReplace) {
        FindReplaceDialog(
            doc = doc,
            currentIndex = currentIndex,
            onDismiss = { showFindReplace = false },
            onJump = { index -> showFindReplace = false; select(index) },
            onApply = { query, replacement, target, all ->
                pushSnapshot(force = true)
                var count = 0
                doc.units.forEachIndexed { i, unit ->
                    val inSource = target != ReplaceScope.TRANSLATION && unit.source.contains(query, ignoreCase = true)
                    val inTranslation = target != ReplaceScope.SOURCE && unit.translation.contains(query, ignoreCase = true)
                    if (!inSource && !inTranslation) return@forEachIndexed
                    if (!all && i != currentIndex) return@forEachIndexed
                    if (inSource) unit.source = unit.source.replace(query, replacement, ignoreCase = true)
                    if (inTranslation) unit.translation = unit.translation.replace(query, replacement, ignoreCase = true)
                    count++
                }
                DocRepository.save(doc, immediate = true)
                revision++
                translatedText = currentUnit()?.translation ?: ""
                showFindReplace = false
                notify("已替换 " + count + " 处")
            }
        )
    }

    if (showBatchConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchConfirm = false },
            title = { Text("批量翻译") },
            text = {
                Text(
                    "将按顺序翻译剩余 " + doc.remainingCount + " " + unitLabel(mode) +
                        "，命中翻译记忆时会自动复用，过程中可随时停止。可能产生较多 API 费用。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showBatchConfirm = false; runBatch() }) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { showBatchConfirm = false }) { Text("取消") } }
        )
    }

    if (batchRunning) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在批量翻译") },
            text = {
                Column {
                    Text("已完成 " + batchDone + " / " + batchTotal + " " + unitLabel(mode))
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (batchTotal <= 0) 0f else (batchDone.toFloat() / batchTotal).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { TextButton(onClick = { cancelBatch = true }) { Text("停止") } }
        )
    }

    if (showDone) {
        AlertDialog(
            onDismissRequest = { showDone = false },
            title = { Text("全部完成") },
            text = { Text("已处理完全部 " + doc.units.size + " " + unitLabel(mode) + "。") },
            confirmButton = { TextButton(onClick = { showDone = false; exit() }) { Text("返回主页") } },
            dismissButton = { TextButton(onClick = { showDone = false }) { Text("继续查看") } }
        )
    }

    // 划词释义浮层
    lookupWord?.let { word ->
        WordDefinitionPopup(
            word = word,
            anchor = lookupAnchor,
            entry = lookupEntry,
            localEntry = WordbookRepository.find(word),
            loading = lookupLoading,
            definitionLanguage = SettingsRepository.settings.definitionLanguage,
            onDismiss = { lookupWord = null },
            onSpeak = { speak(it) },
            onCopy = { copyToClipboard(it, "释义") },
            onSaveToWordbook = { meaning ->
                WordbookRepository.upsert(word, meaning, lookupEntry?.phonetic.orEmpty())
                notify("已加入词库：" + word)
            },
            onRemoveFromWordbook = {
                WordbookRepository.remove(word)
                notify("已从词库移除：" + word)
            },
            onRetry = { lookupAt(word, lookupAnchor) },
            onOpenUrl = { openUrl(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyDocScreen(doc: TranslationDoc, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("该文档没有可翻译内容", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "可以删除后重新导入，或检查源文本是否为空。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UsageBar(
    usage: TranslationService.UsageState,
    memoryHits: Int,
    batchRunning: Boolean,
    batchDone: Int,
    batchTotal: Int,
    modifier: Modifier = Modifier
) {
    val modelName = SettingsRepository.settings.activeModel?.name ?: "未配置模型"
    Card(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (batchRunning) {
                Text(
                    "批量翻译中 " + batchDone + " / " + batchTotal,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modelName,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (memoryHits > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "记忆命中 " + memoryHits,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text("调用 " + usage.calls + " 次", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "输入 " + usage.inputMissTokens + "（命中 " + usage.inputHitTokens + "） · 输出 " + usage.outputTokens,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "≈ " + String.format(Locale.CHINA, "%.4f", usage.totalCost) + " 元",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JumpDialog(
    doc: TranslationDoc,
    currentIndex: Int,
    filter: JumpFilter,
    onFilterChange: (JumpFilter) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    val visible = doc.units.withIndex().filter { (_, unit) ->
        when (filter) {
            JumpFilter.ALL -> true
            JumpFilter.TODO -> !unit.isDone
            JumpFilter.STARRED -> unit.starred
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转 · 共 " + doc.units.size + " " + unitLabel(doc.unitMode)) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JumpFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { onFilterChange(f) },
                            label = { Text(f.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (visible.isEmpty()) {
                    Text(
                        "没有符合条件的条目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        itemsIndexed(visible) { _, entry ->
                            val index = entry.index
                            val unit = entry.value
                            val isCurrent = index == currentIndex
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onSelect(index) }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(
                                            if (unit.isDone) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape
                                        )
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (index + 1).toString() + ". " + unit.source,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (unit.translation.isNotBlank()) {
                                        Text(
                                            unit.translation,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (unit.starred) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindReplaceDialog(
    doc: TranslationDoc,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onApply: (String, String, ReplaceScope, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(ReplaceScope.TRANSLATION) }

    val matches = remember(query, scope, doc.units.size) {
        if (query.isBlank()) emptyList()
        else doc.units.withIndex().filter { (_, unit) ->
            (scope != ReplaceScope.TRANSLATION && unit.source.contains(query, ignoreCase = true)) ||
                (scope != ReplaceScope.SOURCE && unit.translation.contains(query, ignoreCase = true))
        }.map { it.index }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("查找与替换") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("查找内容") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("替换为（可留空删除）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplaceScope.entries.forEach { s ->
                        FilterChip(
                            selected = scope == s,
                            onClick = { scope = s },
                            label = { Text(s.label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (query.isBlank()) "输入内容后开始查找" else "匹配 " + matches.size + " 处",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (matches.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val next = matches.firstOrNull { it > currentIndex } ?: matches.first()
                            onJump(next)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("跳转到下一处匹配") }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = { onApply(query, replacement, scope, false) }) { Text("替换当前") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onApply(query, replacement, scope, true) }) { Text("全部替换") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
