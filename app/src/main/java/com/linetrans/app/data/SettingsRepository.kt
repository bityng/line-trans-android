package com.linetrans.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.linetrans.app.model.AppSettings
import com.linetrans.app.model.DailyStat
import com.linetrans.app.model.PromptTemplates
import com.linetrans.app.model.UsageRecord
import java.time.LocalDate

object SettingsRepository {
    private const val PREFS = "linetrans_settings"
    private const val KEY_SETTINGS = "settings"
    private const val MAX_DAILY_STATS = 30

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    /**
     * 设置对象内部是可变字段（providers/models 直接增删），新旧值往往是同一个实例。
     * 默认的结构相等策略会认为“没有变化”，导致界面不刷新，这里固定为“永不相等”。
     */
    var settings by mutableStateOf(AppSettings(), neverEqualPolicy())
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings = load().sanitized()
    }

    // ---------- 存取 ----------

    private fun load(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { Gson().fromJson(json, AppSettings::class.java) }.getOrNull() ?: AppSettings()
    }

    /** 修正历史数据里可能缺失或越界的字段。 */
    @Suppress("SENSELESS_COMPARISON")
    private fun AppSettings.sanitized(): AppSettings {
        providers.forEach { p -> if (p.models == null) p.models = mutableListOf() }
        if (usageByModel == null) usageByModel = mutableListOf()
        if (dailyStats == null) dailyStats = mutableListOf()
        webServerPort = webServerPort.coerceIn(1024, 65535)
        contextUnits = contextUnits.coerceIn(0, 10)
        maxRetries = maxRetries.coerceIn(0, 5)
        requestTimeoutSec = requestTimeoutSec.coerceIn(10, 600)
        autoSaveMs = autoSaveMs.coerceIn(200, 5000)
        uiScale = uiScale.coerceIn(0.8f, 1.5f)
        // 翻译界面分割线：老设置里没有这两个字段，Gson 会保留默认值（不锁、0.5）；
        // 但历史版本/备份里可能出现 0、负数或 NaN，直接喂给 Modifier.weight() 会崩，必须拦住。
        if (!splitFraction.isFinite()) splitFraction = AppSettings.DEFAULT_SPLIT_FRACTION
        splitFraction = splitFraction.coerceIn(
            AppSettings.MIN_SPLIT_FRACTION,
            AppSettings.MAX_SPLIT_FRACTION
        )
        if (dictionarySource !in listOf("auto", "local", "oxford_web", "oxford_api", "wiktionary", "ai")) {
            dictionarySource = "auto"
        }
        if (definitionLanguage !in listOf("zh", "both", "en")) definitionLanguage = "zh"
        if (promptTemplateId != AppSettings.DEFAULT_PROMPT_ID &&
            PromptTemplates.all.none { it.id == promptTemplateId }
        ) {
            promptTemplateId = AppSettings.DEFAULT_PROMPT_ID
        }
        if (providers.none { it.id == activeProviderId }) activeProviderId = providers.firstOrNull()?.id ?: ""
        if (activeModel == null) activeModelId = activeProvider?.models?.firstOrNull()?.id ?: ""
        return this
    }

    fun save() {
        if (!::prefs.isInitialized) return
        runCatching { prefs.edit().putString(KEY_SETTINGS, gson.toJson(settings)).apply() }
    }

    fun set(s: AppSettings) {
        settings = s
        save()
    }

    fun update(block: (AppSettings) -> AppSettings) {
        settings = block(settings)
        save()
    }

    fun settingsJson(): String = gson.toJson(settings)

    /** 从备份 JSON 恢复设置。 */
    fun restoreJson(json: String): Result<AppSettings> = runCatching {
        val restored = Gson().fromJson(json, AppSettings::class.java) ?: error("设置内容为空")
        val sanitized = restored.sanitized()
        set(sanitized)
        sanitized
    }

    // ---------- 今日进度 ----------

    fun today(): String = LocalDate.now().toString()

    fun dailyCount(): Int = if (settings.dailyDate == today()) settings.dailyCount else 0

    fun bumpDaily(count: Int = 1) {
        if (count <= 0) return
        val today = today()
        update { s ->
            if (s.dailyDate != today) {
                s.dailyDate = today
                s.dailyCount = 0
            }
            s.dailyCount += count
            s
        }
    }

    fun resetDaily() {
        update { s ->
            s.dailyDate = today()
            s.dailyCount = 0
            s
        }
    }

    // ---------- 用量与统计 ----------

    /** 记录一次 API 调用：更新按模型累计用量与当日统计。 */
    fun recordUsage(
        modelId: String,
        modelName: String,
        inputTokens: Int,
        cachedTokens: Int,
        outputTokens: Int,
        cost: Double,
        units: Int = 1
    ) {
        val today = today()
        update { s ->
            val record = s.usageByModel.firstOrNull { it.modelId == modelId }
                ?: UsageRecord(modelId = modelId, modelName = modelName).also { s.usageByModel.add(it) }
            record.modelName = modelName
            record.calls += 1
            record.inputTokens += inputTokens.toLong()
            record.cachedTokens += cachedTokens.toLong()
            record.outputTokens += outputTokens.toLong()
            record.cost += cost

            val stat = s.dailyStats.firstOrNull { it.date == today }
                ?: DailyStat(date = today).also { s.dailyStats.add(it) }
            stat.units += units
            stat.tokens += (inputTokens + outputTokens).toLong()
            stat.cost += cost

            if (s.dailyStats.size > MAX_DAILY_STATS) {
                s.dailyStats.sortBy { it.date }
                while (s.dailyStats.size > MAX_DAILY_STATS) s.dailyStats.removeAt(0)
            }
            s
        }
    }

    /** 近 [days] 天的统计（缺失日期补 0），按时间升序。 */
    fun recentStats(days: Int = 7): List<DailyStat> {
        val byDate = settings.dailyStats.associateBy { it.date }
        val today = LocalDate.now()
        return (days - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            byDate[date] ?: DailyStat(date = date)
        }
    }

    val totalCost: Double get() = settings.usageByModel.sumOf { it.cost }
    val totalInputTokens: Long get() = settings.usageByModel.sumOf { it.inputTokens }
    val totalOutputTokens: Long get() = settings.usageByModel.sumOf { it.outputTokens }

    fun clearUsage() {
        update { s ->
            s.usageByModel.clear()
            s.dailyStats.clear()
            s
        }
    }
}
