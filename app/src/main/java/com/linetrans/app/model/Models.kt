package com.linetrans.app.model

import java.util.Calendar

enum class UnitMode { LINE, SENTENCE }

/** 主题模式。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

/** 导出格式。 */
enum class ExportFormat(val label: String, val extension: String) {
    TXT_TRANSLATED_ONLY("仅译文 TXT", "txt"),
    TXT_BILINGUAL("原文 + 译文 TXT", "txt"),
    TXT_SOURCE_FALLBACK("已译替换源文 TXT", "txt"),
    MARKDOWN_TABLE("Markdown 表格", "md"),
    CSV("CSV 表格", "csv"),
    JSON("JSON（含元数据）", "json")
}

data class TranslationUnit(
    var source: String,
    var translation: String = "",
    var done: Boolean = false,
    /** 收藏 / 星标，便于回看重点句。 */
    var starred: Boolean = false
) {
    /** 是否真的有译文（用于导出时决定“已翻译”）。 */
    val isTranslated: Boolean get() = translation.isNotBlank()

    /** 是否已处理（翻译或标记为“不需要翻译/跳过”），用于进度统计。 */
    val isDone: Boolean get() = done || translation.isNotBlank()
}

data class TranslationDoc(
    val id: String,
    var name: String,
    var folder: String = DEFAULT_FOLDER,
    val units: MutableList<TranslationUnit> = mutableListOf(),
    var unitMode: UnitMode = UnitMode.LINE,
    var sourceText: String = "",
    /** 置顶显示。 */
    var pinned: Boolean = false,
    /** 上次编辑到的位置，重新打开文档时回到这里。 */
    var lastIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    val totalCount: Int get() = units.size
    val translatedCount: Int get() = units.count { it.isDone }
    val remainingCount: Int get() = units.count { !it.isDone }
    val starredCount: Int get() = units.count { it.starred }
    val progress: Float get() = if (totalCount == 0) 0f else translatedCount.toFloat() / totalCount
    val isFinished: Boolean get() = totalCount > 0 && translatedCount >= totalCount

    /** 从 [from] 开始按环形顺序找下一个未完成的单元，全部完成时返回 null。 */
    fun nextUndoneIndex(from: Int = 0): Int? {
        if (units.isEmpty()) return null
        for (offset in units.indices) {
            val i = ((from + offset) % units.size + units.size) % units.size
            if (!units[i].isDone) return i
        }
        return null
    }

    /** 命中翻译记忆：返回已有译文的相同原文（排除 [excludeIndex]）。 */
    fun memoryTranslation(source: String, excludeIndex: Int = -1): String? {
        val key = source.trim()
        if (key.isEmpty()) return null
        return units.withIndex()
            .firstOrNull { (i, u) -> i != excludeIndex && u.source.trim() == key && u.translation.isNotBlank() }
            ?.value?.translation
    }

    companion object {
        const val DEFAULT_FOLDER = "默认"
    }
}

enum class ProviderType(val label: String) {
    OPENAI_COMPAT("OpenAI 兼容"),
    ANTHROPIC("Anthropic"),
    CUSTOM("自定义")
}

data class BillingConfig(
    var inputPrice: Double = 0.0,
    var outputPrice: Double = 0.0,
    var peakMultiplier: Double = 1.0,
    var peakStartHour: Int = 8,
    var peakEndHour: Int = 22
) {
    val currentMultiplier: Double
        get() {
            if (peakMultiplier == 1.0) return 1.0
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val inPeak = if (peakStartHour <= peakEndHour) h in peakStartHour until peakEndHour
                else (h >= peakStartHour || h < peakEndHour)
            return if (inPeak) peakMultiplier else 1.0
        }

    /** 是否配置了价格（未配置时不做费用估算）。 */
    val hasPrice: Boolean get() = inputPrice > 0.0 || outputPrice > 0.0
}

data class ModelConfig(
    val id: String,
    var name: String,
    var providerId: String,
    var billing: BillingConfig = BillingConfig(),
    var maxTokens: Int = 4096,
    var temperature: Double = 0.2,
    var topP: Double = 1.0
)

data class ProviderConfig(
    val id: String,
    var name: String,
    var type: ProviderType = ProviderType.OPENAI_COMPAT,
    var baseUrl: String = "",
    var apiKey: String = "",
    var models: MutableList<ModelConfig> = mutableListOf()
) {
    fun findModel(id: String): ModelConfig? = models.firstOrNull { it.id == id }
}

/** 单个模型的累计用量。 */
data class UsageRecord(
    var modelId: String,
    var modelName: String,
    var calls: Int = 0,
    var inputTokens: Long = 0,
    var cachedTokens: Long = 0,
    var outputTokens: Long = 0,
    var cost: Double = 0.0
)

/** 每日统计，用于进度图表。 */
data class DailyStat(
    var date: String = "",
    var units: Int = 0,
    var tokens: Long = 0,
    var cost: Double = 0.0
)

data class AppSettings(
    // —— AI ——
    var providers: MutableList<ProviderConfig> = mutableListOf(),
    var activeProviderId: String = "",
    var activeModelId: String = "",
    var detectLanguage: Boolean = true,
    var sourceLang: String = "auto",
    var targetLang: String = "zh-CN",
    /** 用户可编辑的系统提示词，支持 {sourceLang} {targetLang} {docName} {mode} {glossary} 占位符。 */
    var systemPrompt: String = "",
    /** 使用的提示词模板 id（default / literal / literary / technical / subtitle / custom）。 */
    var promptTemplateId: String = DEFAULT_PROMPT_ID,
    /** 术语表，每行一条“原文=译文”。 */
    var glossary: String = "",
    /** AI 翻译时携带的前文参考句数。 */
    var contextUnits: Int = 3,
    /** AI 翻译完成后是否自动跳到下一句。 */
    var autoAdvance: Boolean = true,
    /** 相同原文自动复用已有译文。 */
    var translationMemory: Boolean = true,
    /** 划词查词：触摸单词弹出释义。 */
    var wordLookupEnabled: Boolean = true,
    /** 词典来源：auto / oxford_web / oxford_api / wiktionary / ai。 */
    var dictionarySource: String = "auto",
    /** 优先使用内置/导入的离线词库。 */
    var localDictionaryEnabled: Boolean = true,
    /** 释义语言：zh（只显示中文）/ both（中英对照）/ en（英文原版）。 */
    var definitionLanguage: String = "zh",
    /** 用 AI 为英文释义补一条中文解释。 */
    var dictionaryAiExplain: Boolean = true,
    var oxfordAppId: String = "",
    var oxfordAppKey: String = "",

    // —— 界面 ——
    var themeMode: ThemeMode = ThemeMode.SYSTEM,
    var dynamicColor: Boolean = true,
    /** 界面缩放（0.85 / 1.0 / 1.15 / 1.3）。 */
    var uiScale: Float = 1.0f,
    var animations: Boolean = true,
    var showProgressRing: Boolean = true,

    // —— 翻译行为 ——
    var keepScreenOn: Boolean = false,
    var swipeToSwitch: Boolean = true,
    var autoSaveMs: Int = 700,

    // —— 翻译界面分割线（原文/译文分栏）——
    /** 分割线是否锁定；锁定后滑杆禁用，避免误拖。 */
    var dividerLocked: Boolean = false,
    /** 原文区占内容高度的比例，取值必须落在 MIN/MAX_SPLIT_FRACTION 之间。 */
    var splitFraction: Float = DEFAULT_SPLIT_FRACTION,

    // —— 网络 ——
    var requestTimeoutSec: Int = 120,
    var maxRetries: Int = 2,
    var proxyUrl: String = "",
    var userAgent: String = "",

    // —— 数据 ——
    var storageDirUri: String = "",
    var dailyGoal: Int = 0,
    var defaultExportFormat: ExportFormat = ExportFormat.TXT_BILINGUAL,

    // —— 局域网 Web 服务 ——
    var webServerPort: Int = 8080,
    var webServerEnabled: Boolean = false,
    var webServerToken: String = "",
    var webServerAutoStart: Boolean = false,

    // —— 统计 ——
    var dailyDate: String = "",
    var dailyCount: Int = 0,
    var usageByModel: MutableList<UsageRecord> = mutableListOf(),
    var dailyStats: MutableList<DailyStat> = mutableListOf()
) {
    val activeProvider: ProviderConfig? get() = providers.firstOrNull { it.id == activeProviderId }
    val activeModel: ModelConfig? get() = activeProvider?.findModel(activeModelId)

    val allModels: List<ModelConfig> get() = providers.flatMap { it.models }

    /** 实际使用的系统提示词（未自定义时用默认模板）。 */
    val effectivePrompt: String
        get() = systemPrompt.ifBlank { PromptTemplates.DEFAULT }

    val glossaryEntries: List<Pair<String, String>>
        get() = glossary.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val sep = t.indexOfFirst { it == '=' || it == '\t' || it == '：' || it == ':' }
            if (sep <= 0) return@mapNotNull null
            val key = t.substring(0, sep).trim()
            val value = t.substring(sep + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }

    companion object {
        const val DEFAULT_PROMPT_ID = "default"

        /** 分割比例的默认值（原文与译文各占一半）。 */
        const val DEFAULT_SPLIT_FRACTION = 0.5f
        /** 分割比例下限。 */
        const val MIN_SPLIT_FRACTION = 0.2f
        /** 分割比例上限。Modifier.weight() 不能传 0，也不能越界，故限制在 0.2~0.8。 */
        const val MAX_SPLIT_FRACTION = 0.8f
    }
}

/** 内置系统提示词模板。 */
object PromptTemplates {
    const val DEFAULT = "你是专业翻译。请把用户提供的内容从 {sourceLang} 翻译成 {targetLang}。" +
        "只输出译文，不要解释，不要添加多余内容，保持原有格式与换行。"

    data class Template(val id: String, val name: String, val description: String, val prompt: String)

    val all: List<Template> = listOf(
        Template(
            id = "default",
            name = "通用（默认）",
            description = "忠实直译，保持格式，适合大多数场景",
            prompt = DEFAULT
        ),
        Template(
            id = "literal",
            name = "逐字对照",
            description = "尽量保留原文语序与结构，适合精读",
            prompt = "你是严谨的对照翻译工具。把 {sourceLang} 内容逐句翻译成 {targetLang}，" +
                "尽量保留原文语序与结构，术语前后一致。只输出译文，不要解释。"
        ),
        Template(
            id = "literary",
            name = "文学润色",
            description = "译文自然流畅，适合小说、散文",
            prompt = "你是文学翻译。把 {sourceLang} 内容翻译成 {targetLang}，" +
                "译文要自然流畅、符合目标语言表达习惯，保留原文语气与情感。" +
                "只输出译文，不要解释。"
        ),
        Template(
            id = "technical",
            name = "技术文档",
            description = "术语准确、语句简洁，保留代码与标记",
            prompt = "你是技术文档翻译。把 {sourceLang} 内容翻译成 {targetLang}，" +
                "术语准确、语句简洁；代码、命令、变量名、占位符保持原样不翻译。" +
                "只输出译文，不要解释。"
        ),
        Template(
            id = "subtitle",
            name = "字幕口语",
            description = "口语化、长度贴近原文，适合字幕",
            prompt = "你是字幕翻译。把 {sourceLang} 内容翻译成 {targetLang}，" +
                "使用口语化表达，长度尽量贴近原文，不要出现换行。" +
                "只输出译文，不要解释。"
        )
    )

    fun byId(id: String): Template = all.firstOrNull { it.id == id } ?: all.first()
}
