package com.linetrans.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linetrans.app.data.DocRepository
import com.linetrans.app.data.DocSort
import com.linetrans.app.data.ExportManager
import com.linetrans.app.data.SettingsRepository
import com.linetrans.app.data.StorageManager
import com.linetrans.app.model.ExportFormat
import com.linetrans.app.model.TranslationDoc
import com.linetrans.app.model.TranslationUnit
import com.linetrans.app.model.UnitMode
import com.linetrans.app.util.TextParser
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class PendingImport(
    val name: String,
    val text: String,
    val hasTranslated: Boolean
)

private enum class DocFilter(val label: String) {
    ALL("全部"),
    DOING("进行中"),
    DONE("已完成"),
    STARRED("有收藏")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenDoc: (String, Int, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWebServer: () -> Unit
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var expandedId by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var exportFor by remember { mutableStateOf<TranslationDoc?>(null) }
    var folderDialogFor by remember { mutableStateOf<TranslationDoc?>(null) }
    var renameFor by remember { mutableStateOf<TranslationDoc?>(null) }
    var deleteFor by remember { mutableStateOf<TranslationDoc?>(null) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(DocFilter.ALL) }
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(DocRepository.sortMode) }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    val textPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val name = StorageManager.displayName(context, uri)
            val text = StorageManager.readText(context, uri)
            if (text.isBlank()) throw IllegalStateException("文件内容为空")
            val hasTranslated = text.lines().any { TextParser.splitSourceTranslation(it) != null }
            PendingImport(name, text, hasTranslated)
        }.onSuccess { pendingImport = it }
            .onFailure { notify("导入失败：" + (it.message ?: "无法读取文件")) }
    }

    fun openTextPicker() = textPicker.launch(arrayOf("text/*", "application/json"))

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            SettingsRepository.update { it.copy(storageDirUri = uri.toString()) }
            notify("已设置数据文件夹")
            openTextPicker()
        }
    }

    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val json = StorageManager.readText(context, uri)
            com.linetrans.app.data.BackupManager.restore(json).getOrThrow()
        }.onSuccess { result ->
            notify("已恢复 " + result.docs + " 篇文档" + if (result.settingsRestored) "（含设置）" else "")
        }
            .onFailure { notify("恢复失败：" + (it.message ?: "文件格式不正确")) }
    }

    fun startImport() {
        if (SettingsRepository.settings.storageDirUri.isBlank()) folderPicker.launch(null) else openTextPicker()
    }

    fun importFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        if (text.isBlank()) {
            notify("剪贴板中没有文本")
            return
        }
        val hasTranslated = text.lines().any { TextParser.splitSourceTranslation(it) != null }
        pendingImport = PendingImport("剪贴板文本", text, hasTranslated)
    }

    fun shareDoc(doc: TranslationDoc) {
        val text = ExportManager.shareText(doc)
        if (text.isBlank()) {
            notify("没有可分享的内容")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, doc.name)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "分享译文")) }
            .onFailure { notify("没有可用的分享应用") }
    }

    val docs = DocRepository.docs

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.surface
                                        )
                                    )
                                )
                                .padding(horizontal = 20.dp, vertical = 24.dp)
                        ) {
                            Text("逐行翻译", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            val goal = SettingsRepository.settings.dailyGoal
                            val done = SettingsRepository.dailyCount()
                            Text(
                                if (goal > 0) "今日 $done / $goal 句" else "今日已完成 $done 句",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        Text(
                            "文件夹",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
                        )
                    }
                    item {
                        DrawerRow(
                            icon = Icons.Default.Folder,
                            title = "全部文档",
                            count = docs.size,
                            selected = selectedFolder == null,
                            onClick = {
                                selectedFolder = null
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                    items(DocRepository.folders(), key = { "folder-" + it }) { folder ->
                        DrawerRow(
                            icon = Icons.Default.Folder,
                            title = folder,
                            count = docs.count { it.folder == folder },
                            selected = selectedFolder == folder,
                            onClick = {
                                selectedFolder = folder
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        DrawerRow(
                            icon = Icons.Default.Settings,
                            title = "设置",
                            count = null,
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                onOpenSettings()
                            }
                        )
                        DrawerRow(
                            icon = Icons.Default.Computer,
                            title = "网页翻译台",
                            count = null,
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                onOpenWebServer()
                            }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (searchActive) {
                    TopAppBar(
                        title = {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("搜索文档名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                searchActive = false
                                query = ""
                            }) { Icon(Icons.Default.Close, contentDescription = "关闭搜索") }
                        }
                    )
                } else {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        title = {
                            Column {
                                Text("逐行翻译", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    docs.size.toString() + " 篇文档 · " +
                                        docs.sumOf { it.translatedCount } + " / " + docs.sumOf { it.totalCount } + " 句已完成",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "菜单")
                            }
                        },
                        actions = {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "搜索")
                            }
                            Box {
                                IconButton(onClick = { sortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    DocSort.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    (if (sortMode == mode) "✓ " else "   ") + mode.label
                                                )
                                            },
                                            onClick = {
                                                sortMode = mode
                                                DocRepository.setSort(mode)
                                                sortMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { addMenu = true }) {
                                    Icon(Icons.Default.Add, contentDescription = "添加文档")
                                }
                                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("导入文本文件") },
                                        onClick = { addMenu = false; startImport() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("从剪贴板新建") },
                                        onClick = { addMenu = false; importFromClipboard() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("恢复备份…") },
                                        onClick = {
                                            addMenu = false
                                            backupPicker.launch(arrayOf("application/json", "text/*"))
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        ) { padding ->
            val filtered = docs
                .filter { selectedFolder == null || it.folder == selectedFolder }
                .filter {
                    when (filter) {
                        DocFilter.ALL -> true
                        DocFilter.DOING -> !it.isFinished
                        DocFilter.DONE -> it.isFinished
                        DocFilter.STARRED -> it.starredCount > 0
                    }
                }
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }

            Box(Modifier.padding(padding).fillMaxSize()) {
                if (docs.isEmpty()) {
                    EmptyState(onAdd = { startImport() })
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            TodayHeroCard(onReset = { SettingsRepository.resetDaily() })
                        }
                        item {
                            FlowRow(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DocFilter.entries.forEach { f ->
                                    FilterChip(
                                        selected = filter == f,
                                        onClick = { filter = f },
                                        label = { Text(f.label, maxLines = 1) }
                                    )
                                }
                            }
                        }
                        if (selectedFolder != null) {
                            item {
                                AssistChip(
                                    onClick = { selectedFolder = null },
                                    label = { Text("文件夹：" + selectedFolder + "  ×", maxLines = 1) }
                                )
                            }
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("没有符合条件的文档", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(filtered, key = { it.id }) { doc ->
                            DocCard(
                                doc = doc,
                                modifier = Modifier.animateItem(placementSpec = Motion.gentle()),
                                expanded = expandedId == doc.id,
                                onToggle = { expandedId = if (expandedId == doc.id) null else doc.id },
                                onContinue = { onOpenDoc(doc.id, doc.nextUndoneIndex(0) ?: 0, false) },
                                onView = {
                                    val last = doc.lastIndex.coerceIn(0, (doc.totalCount - 1).coerceAtLeast(0))
                                    onOpenDoc(doc.id, last, true)
                                },
                                onPin = {
                                    doc.pinned = !doc.pinned
                                    DocRepository.save(doc, immediate = true)
                                    DocRepository.sort()
                                    notify(if (doc.pinned) "已置顶 " + doc.name else "已取消置顶")
                                },
                                onExport = { exportFor = doc },
                                onShare = { shareDoc(doc) },
                                onMoveFolder = { folderDialogFor = doc },
                                onRename = { renameFor = doc },
                                onDelete = { deleteFor = doc }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingImport?.let { pending ->
        ImportDialog(
            pending = pending,
            onDismiss = { pendingImport = null },
            onConfirm = { mode, skipTranslated, smartClean ->
                val doc = createDocFromText(pending.name, pending.text, mode, skipTranslated, smartClean)
                DocRepository.save(doc, immediate = true)
                pendingImport = null
                notify("已导入 " + doc.name + "（" + doc.totalCount + " " + unitLabel(mode) + "）")
            }
        )
    }

    exportFor?.let { doc ->
        ExportDialog(
            doc = doc,
            onDismiss = { exportFor = null },
            onExport = { format ->
                runCatching { ExportManager.export(context, doc, format) }
                    .onSuccess {
                        exportFor = null
                        notify("已导出：$it")
                    }
                    .onFailure { notify("导出失败：" + (it.message ?: "未知错误")) }
            },
            onShare = {
                exportFor = null
                shareDoc(doc)
            }
        )
    }

    folderDialogFor?.let { doc ->
        FolderDialog(
            doc = doc,
            folders = DocRepository.folders(),
            onDismiss = { folderDialogFor = null },
            onConfirm = { newFolder ->
                doc.folder = newFolder.ifBlank { TranslationDoc.DEFAULT_FOLDER }
                DocRepository.save(doc, immediate = true)
                folderDialogFor = null
                notify("已移动到文件夹：" + doc.folder)
            }
        )
    }

    renameFor?.let { doc ->
        RenameDialog(
            doc = doc,
            onDismiss = { renameFor = null },
            onConfirm = { newName ->
                doc.name = newName
                DocRepository.save(doc, immediate = true)
                renameFor = null
                notify("已重命名为 " + newName)
            }
        )
    }

    deleteFor?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除文档") },
            text = { Text("确定要删除「" + doc.name + "」吗？该操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    DocRepository.delete(doc.id)
                    deleteFor = null
                    notify("已删除 " + doc.name)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun DrawerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = Motion.move(240),
        label = "drawer-bg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (count != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            ) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TodayHeroCard(onReset: () -> Unit) {
    val docs = DocRepository.docs
    val goal = SettingsRepository.settings.dailyGoal
    val done = SettingsRepository.dailyCount()
    val ratio = if (goal <= 0) 0f else (done.toFloat() / goal).coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(ratio, animationSpec = Motion.value(620), label = "daily-progress")
    val totalTranslated = docs.sumOf { it.translatedCount }
    val totalUnits = docs.sumOf { it.totalCount }
    val starred = docs.sumOf { it.starredCount }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (goal > 0) animatedRatio else 1f },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    strokeWidth = 7.dp
                )
                Text(
                    if (goal > 0) (animatedRatio * 100).roundToInt().toString() + "%" else done.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("今日翻译", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (goal > 0) "已完成 $done / $goal 句" else "已完成 $done 句",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "累计 " + totalTranslated + " / " + totalUnits + " 句 · 收藏 " + starred + " 句",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (done > 0) {
                    TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("重置今日进度", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("还没有导入文本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "支持 .txt / .md / .srt / .csv，导入后可按行或按句对照翻译",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导入文本")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocCard(
    doc: TranslationDoc,
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onToggle: () -> Unit,
    onContinue: () -> Unit,
    onView: () -> Unit,
    onPin: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onMoveFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val animatedProgress by animateFloatAsState(doc.progress, animationSpec = Motion.value(560), label = "doc-progress")
    Card(
        modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 5.dp,
                        color = if (doc.isFinished) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        (animatedProgress * 100).roundToInt().toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (doc.pinned) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "已置顶",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            doc.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 用 FlowRow：文件夹名很长时换行，而不是把后面的标记挤出卡片
                    FlowRow(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MetaChip(doc.folder)
                        MetaChip(if (doc.unitMode == UnitMode.SENTENCE) "逐句" else "逐行")
                        if (doc.starredCount > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(doc.starredCount.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        doc.translatedCount.toString() + " / " + doc.totalCount + " " + unitLabel(doc.unitMode) +
                            " · " + formatTime(doc.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPin) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = if (doc.pinned) "取消置顶" else "置顶",
                        tint = if (doc.pinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(Icons.Default.ExpandMore, contentDescription = if (expanded) "收起" else "展开")
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = Motion.gentle()) + fadeIn(animationSpec = Motion.enter(220)),
                exit = shrinkVertically(animationSpec = Motion.exit(200)) + fadeOut(animationSpec = Motion.exit(200))
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onContinue) {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (doc.isFinished) "重新翻译" else if (doc.translatedCount > 0) "继续翻译" else "开始翻译")
                        }
                        FilledTonalButton(onClick = onView) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("查看")
                        }
                        OutlinedButton(onClick = onExport) { Text("导出") }
                        OutlinedButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("分享")
                        }
                        OutlinedButton(onClick = onMoveFolder) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("文件夹")
                        }
                        OutlinedButton(onClick = onRename) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重命名")
                        }
                        OutlinedButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 140.dp).padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ImportDialog(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onConfirm: (UnitMode, Boolean, Boolean) -> Unit
) {
    var mode by remember { mutableStateOf(UnitMode.LINE) }
    var skipTranslated by remember { mutableStateOf(pending.hasTranslated) }
    var smartClean by remember { mutableStateOf(false) }
    val cleaned = remember(pending.text, smartClean) {
        if (smartClean) TextParser.smartClean(pending.text) else pending.text
    }
    val count = remember(cleaned, mode) { TextParser.parse(cleaned, mode).size }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 " + pending.name) },
        text = {
            Column {
                Text("切分方式", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row {
                    FilterChip(
                        selected = mode == UnitMode.LINE,
                        onClick = { mode = UnitMode.LINE },
                        label = { Text("逐行") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == UnitMode.SENTENCE,
                        onClick = { mode = UnitMode.SENTENCE },
                        label = { Text("逐句") }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("智能清理", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "去掉字幕时间轴、序号行与 Markdown 标记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = smartClean, onCheckedChange = { smartClean = it })
                }
                if (pending.hasTranslated) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("略过已有翻译", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "检测到「原文 ⇥ 译文」格式的内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = skipTranslated, onCheckedChange = { skipTranslated = it })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "共 " + count + " " + unitLabel(mode) + "需要翻译",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode, skipTranslated, smartClean) }) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FolderDialog(
    doc: TranslationDoc,
    folders: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(doc.folder) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到文件夹") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("已有文件夹", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    FolderChips(folders) { name = it }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "填写新名称即可新建文件夹。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderChips(folders: List<String>, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { f ->
            AssistChip(onClick = { onPick(f) }, label = { Text(f, maxLines = 1) })
        }
    }
}

@Composable
private fun RenameDialog(doc: TranslationDoc, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(doc.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名文档") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("文档名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                if (n.isNotEmpty()) onConfirm(n)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportDialog(
    doc: TranslationDoc,
    onDismiss: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onShare: () -> Unit
) {
    var selected by remember { mutableStateOf(SettingsRepository.settings.defaultExportFormat) }
    val preview = remember(selected, doc.units.size) { ExportManager.buildText(doc, selected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出 " + doc.name) },
        text = {
            Column {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ExportFormat.entries.forEach { format ->
                        FilterChip(
                            selected = selected == format,
                            onClick = { selected = format },
                            label = { Text(format.label, maxLines = 1) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        preview.lineSequence().take(6).joinToString("\n").ifBlank { "（内容为空）" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "共 " + preview.lines().size + " 行 · 文件名：" + ExportManager.buildFileName(doc, selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (SettingsRepository.settings.storageDirUri.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "尚未设置数据文件夹，保存前请先在设置中选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onExport(selected) }) { Text("保存到文件夹") } },
        dismissButton = { TextButton(onClick = onShare) { Text("分享") } }
    )
}

private fun formatTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> (diff / 60_000L).toString() + " 分钟前"
        diff < 86_400_000L -> (diff / 3_600_000L).toString() + " 小时前"
        diff < 604_800_000L -> (diff / 86_400_000L).toString() + " 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun createDocFromText(
    name: String,
    text: String,
    mode: UnitMode,
    skipTranslated: Boolean,
    smartClean: Boolean
): TranslationDoc {
    val prepared = if (smartClean) TextParser.smartClean(text) else text
    val units = TextParser.parse(prepared, mode).map { line ->
        if (skipTranslated) {
            val pair = TextParser.splitSourceTranslation(line.source)
            if (pair != null) TranslationUnit(pair.first, pair.second) else line
        } else {
            line
        }
    }.toMutableList()
    return TranslationDoc(
        id = DocRepository.newId(),
        name = name.removeSuffix(".txt").ifBlank { "未命名文档" },
        units = units,
        unitMode = mode,
        sourceText = prepared
    )
}
