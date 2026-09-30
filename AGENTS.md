# AGENTS.md · 安卓客户端

> 给 AI 助手 / 新同学的入口文件。完整背景、架构、坑位清单与 Roadmap 见
> [`F:\逐行翻译\项目说明与开发指南.md`](file:///F:/%E9%80%90%E8%A1%8C%E7%BF%BB%E8%AF%91/%E9%A1%B9%E7%9B%AE%E8%AF%B4%E6%98%8E%E4%B8%8E%E5%BC%80%E5%8F%91%E6%8C%87%E5%8D%97.md)
> （或仓库内 README.md 的「相关仓库」一节）。**动手前请先读那份文档。**

## 这是什么

LineTrans 安卓客户端：Kotlin + Jetpack Compose 的逐行 / 逐句对照翻译应用，
带 AI 翻译、划词查义（内置离线词库）、翻译记忆、导出与**内置网页翻译台**。

## 硬性约定

- 许可：**AGPL-3.0-or-later**，不要改回 MIT；不要删除「关于」页的两个仓库入口（AGPL 第 13 条）。
- 不要提交 `keystore.properties` 或 `*.keystore`（签名文件只在本地）。
- 版本号在 `app/build.gradle.kts`，每次发版 `versionCode` +1。
- 界面文案用中文，直接写在 Compose 代码里。

## 改代码前必看

| 想改什么 | 先看哪个文件 |
| --- | --- |
| 数据模型 / 设置项 | `model/Models.kt`、`data/SettingsRepository.kt`（记得在 `sanitized()` 里兼容老数据） |
| 逐行 / 逐句切分 | `util/TextParser.kt`（规则细节见项目文档 §5，**改完要跑用例表**） |
| 翻译界面交互 | `ui/TranslationScreen.kt`（最复杂，谨慎改） |
| 主界面 / 文档列表 | `ui/HomeScreen.kt` |
| AI 调用 / 提示词 | `ai/TranslationService.kt` |
| 划词查义 / 词库 | `ui/WordLookup.kt`、`ai/DictionaryService.kt`、`ai/LocalDictionary.kt` |
| 局域网网页台后端 | `server/WebApi.kt`（与 line-trans-web 的 API 必须一致） |
| 网页界面 | `app/src/main/assets/web/`（与 line-trans-web 的 `public/` 必须一致） |

## 两条不能踩的坑

1. `SettingsRepository.settings` 用 `neverEqualPolicy()`，改了别动，否则设置界面不刷新。
2. `Modifier.weight()` 不能传 0，滑杆/分栏要 clamp。

## 构建

```bash
./gradlew assembleRelease     # 需要 JDK 17 + ANDROID_HOME + keystore.properties
```

命令行构建请用**纯英文路径**（中文路径会让 Kotlin 的参数文件编码出错）；
若遇到「明明存在的函数报 Unresolved reference」，删掉 `app/build` 全量重建。
