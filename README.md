# 逐行翻译 (LineTrans)

一款用于逐行 / 逐句对照翻译的安卓原生应用，基于 **Jetpack Compose** 开发。
支持多家 AI 接口、翻译记忆、批量翻译、术语表与自定义系统提示词。

## 相关仓库

- **line-trans-android**（本仓库）：安卓客户端，内置局域网网页翻译台
- [line-trans-web](https://github.com/bityng/line-trans-web)：独立网页服务端（Node，零依赖），
  可在电脑 / NAS 上单独运行，与本客户端**共用同一套网页界面与 HTTP API**

`app/src/main/assets/web/` 是网页翻译台的界面文件，与 line-trans-web 仓库的 `public/` 保持一致。

## 界面

- **主界面**：今日进度环、文档搜索、全部 / 进行中 / 已完成 / 有收藏 筛选、排序（最近更新 / 名称 / 进度）、置顶、
  环形进度卡片、展开后的卡片操作动画。
- **翻译界面**：上下分栏（分割线可调可锁）、逐行 / 逐句切换（按原文锚定保留位置，不会跳回第一句）、
  撤销重做、查找替换、跳转列表（可筛选未完成 / 收藏）、朗读、收藏、左右滑动切换上下句、Ctrl+Enter 下一句；
  每篇文档会记住上次读到的位置，键盘弹出时自动收起次要区域保证输入框不被遮挡。
- **查看模式**：主界面「查看」进入只读预览，不会误改译文，可一键「开始编辑」切换。
- **设置页**：分为 **AI 翻译 / 界面 / 数据 / 高级 / 关于** 五个分类，切换带滑动动画。
- **转场动画**：页面进出、卡片展开、列表增删、进度条与按钮状态均有过渡动效（可在设置关闭）。

## 功能

### AI 翻译
- OpenAI 兼容 / Anthropic / 自定义接口，内置常用服务商地址预设。
- **自定义系统提示词**：支持 `{sourceLang}` `{targetLang}` `{docName}` `{mode}` `{glossary}` 占位符，
  内置 5 套模板（通用 / 逐字对照 / 文学润色 / 技术文档 / 字幕口语），可一键恢复默认。
- **术语表**：每行一条「原文=译文」，翻译时强制使用。
- **翻译记忆**：同一原文已有译文时直接复用，不消耗 token。
- 参数：上下文参考条数、Temperature、Top-P、最大输出 tokens、高峰倍率与时段（用于费用估算）。
- 网络：请求超时、失败重试次数、HTTP 代理、自定义 User-Agent，设置页可一键**测试连接**并显示耗时。
- 失败自动重试（429 / 5xx / 网络异常），错误信息可读化。

### 文档与数据处理

#### 逐句 / 逐行切分

- **逐行**：每个自然行一句（空行忽略），适合字幕、清单类文本。
- **逐句**：更聪明的断句规则 ——
  - 空行分段；**段落内的排版换行会先合并**（英文之间补空格、中日韩之间不补），避免半句话被切开
  - 只在真正的句末标点断开：`。！？!?…；`（不再在英文分号 `;` 处断开）
  - 保护缩写（`Mr.` `Dr.` `U.S.` `e.g.` `a.m.` …）、首字母缩写（`J. K.`）、小数点（`3.14`）与网址（`example.com`）
  - 连续的点压成一个 `…`，句末的引号 / 括号（`”』）` 等）跟着上一句
  - 只有标点或单字的碎片会并回上一句
- 两种模式互相切换时按原文锚定，保留已有译文与当前位置。
- 导入 `.txt` / `.md` / `.srt` / `.csv`，支持**智能清理**（去掉字幕时间轴、序号行、Markdown 标记）
  与「略过已有翻译」，可选逐行或逐句切分。
- 导出 6 种格式：仅译文 / 原文+译文 / 已译替换源文 / Markdown 表格 / CSV / JSON，导出前可预览。
- 分享：单篇分享、分享当前句；支持复制全部译文。
- **备份与恢复**：导出包含全部文档与设置的 JSON，恢复为合并模式。
- 文档按文件夹分类，可重命名、移动、置顶、删除（带二次确认）。

### 统计
- 今日进度（按天自动归零）与每日目标进度环。
- 累计输入 / 输出 token、费用，按模型拆分，近 7 天完成量柱状图。

### 划词词典（点词查义）
- 在原文里**点一下单词**，会在该词上方浮出释义卡片；只读「查看」模式下的译文同样可点。
- **内置离线词库**：随应用打包 4 万常用词（ECDICT，MIT）+ 8.4 万条词形还原（ran → run），
  点词**秒出中文释义**，不联网、不受网络影响；可在设置里换成更大的词典
  （支持 ECDICT 的 CSV 或 `单词 TAB 音标 TAB 释义` 文本，导入后立即生效）。
- 词典来源可选：**本地词库** / 牛津词典网页（免密钥）/ 牛津官方 API（填 app_id / app_key）/
  Wiktionary / AI 释义；「自动」按顺序回退，保证查得到。
- **释义默认中文**，英文释义折叠起来（可切成中英对照 / 英文原版）；本地词库没有的词条才联网查询。
- 可选「用 AI 补充中文释义」，英文释义上方会多一行中文解释。
- **我的词库**：查词浮层里一键「加入词库」，查词时优先命中；
  设置里可批量编辑，支持 `单词=释义` / `单词=音标=释义` 文本导入与导出。

> 词库数据（`assets/dict/core.tsv`、`lemma.tsv`）由 `tools/build-local-dict.mjs` 依据
> [ECDICT](https://github.com/skywind3000/ECDICT) 生成，可自行重新生成：
> `node tools/build-local-dict.mjs ecdict.csv lemma.en.txt app/src/main/assets/dict`

### 局域网网页翻译台

在设置 → 高级中开启「局域网 Web 服务」后，手机会启动前台服务监听端口：

- `http://<手机IP>:<端口>/` —— **网页翻译台**：同一局域网的电脑 / 平板浏览器直接继续翻译：
  左侧文档列表、右侧逐句「原文 + 译文」编辑，支持自动保存、单句 AI 翻译、批量翻译剩余、
  收藏与筛选、逐行 / 逐句切换、导出对照 TXT / Markdown / CSV / JSON。
- 支持**访问令牌**（填写后需带 `?token=xxx`）、自定义端口、应用启动后自动开启。
- 网页界面与 API 与独立服务端 [line-trans-web](https://github.com/bityng/line-trans-web) 完全一致，
  也可以把 Web 服务端单独跑在电脑上使用。

## 技术栈

- Kotlin 1.9.24 / AGP 8.5.2 / Gradle 8.7（已内置 wrapper）
- Jetpack Compose (Material3) + Navigation Compose
- OkHttp + Gson（AI API 调用）
- NanoHTTPD（局域网网页翻译台服务器）
- Android Storage Access Framework（文件夹选择与读写）

minSdk 26，targetSdk 34。

## 构建与运行

1. 使用 **Android Studio** 打开项目目录，点击 Run。
2. 命令行：
   ```
   ./gradlew assembleDebug
   ./gradlew assembleRelease    # 需要在 keystore.properties 中配置签名
   ```

命令行构建需要 `JAVA_HOME`（JDK 17）与 `ANDROID_HOME`。
若项目路径包含中文，`gradle.properties` 中的 `android.overridePathCheck=true` 已允许构建；
但 Windows 下命令行构建仍可能因 Kotlin 编译器对非 ASCII 路径的处理失败，建议使用 Android Studio，
或把项目放在纯英文路径下再构建。

## 目录结构

```
LineTrans/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── assets/web/                     # 网页翻译台（与 line-trans-web 的 public/ 一致）
│       ├── java/com/linetrans/app/
│       │   ├── MainActivity.kt
│       │   ├── ai/TranslationService.kt    # AI 翻译与提示词构建
│       │   ├── data/                       # 设置、文档、存储、导出、备份
│       │   ├── model/Models.kt             # 数据模型与提示词模板
│       │   ├── server/                     # 局域网网页翻译台：静态资源 + API
│       │   ├── ui/                         # 主界面、翻译界面
│       │   │   └── settings/               # 设置页（分类 Tab）
│       │   └── util/                       # 文本解析、费用计算、网络
│       └── res/                            # 主题、图标、字符串
├── gradlew / gradlew.bat / gradle/wrapper
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 说明

- 文档数据保存在应用私有目录（`files/docs/*.json`），写入经过防抖并放在 IO 线程。
- 费用按输入的每百万 token 价格估算，未配置价格时只统计 token。

## 许可

本项目使用 **GNU Affero General Public License v3.0（AGPL-3.0-or-later）** 开源，完整条款见 [LICENSE](LICENSE)。

> AGPL 对**网络服务**有额外要求：把本项目（或它的修改版）部署成网络服务供他人使用时，
> 必须向使用者提供对应源码。网页翻译台与安卓端的「关于」里都已保留仓库入口。
> 内置离线词典数据来自 [ECDICT](https://github.com/skywind3000/ECDICT)（MIT），其数据部分仍遵循原许可。
