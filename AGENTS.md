# AGENTS.md

战地1 (Battlefield 1) 服务器管理 Android 应用。单模块（`:app`），Kotlin + Jetpack Compose (Material3)，包名 `com.bf1.admin.tool`。通过 EA / Battlelog (Sparta Gateway) 的 JSON-RPC 与 Blaze 协议操作服务器。

## 构建与测试

- 构建：`./gradlew assembleDebug`；正式包：`./gradlew assembleRelease`
- 单元测试（纯 JUnit4 本地测试，无 instrumented 测试）：`./gradlew testDebugUnitTest`
- 单个测试：`./gradlew testDebugUnitTest --tests "com.bf1.admin.tool.data.remote.RotatedCookiesTest"`
- 无 lint 配置。改代码后跑 `testDebugUnitTest` 即是最快验证。
- CI：`.github/workflows/android-release.yml`，打 tag（`V*`/`v*`）或手动触发时构建签名 Release APK 并创建 GitHub Release。

## 关键坑

- **项目路径含中文**（如 `D:\战地1管理工具\...`）：Windows 下 test worker 的 @argfile 以 GBK 解析 classpath，导致 `ClassNotFoundException`。本机已在 `~/.gradle/gradle.properties` 配置 `bf1.buildDir=D:/bf1build`，由 `settings.gradle.kts:37-46` 读取（必须与项目同盘符，跨盘符会触发 KSP "cleanFilenames lateinit" 崩溃）。**不要删这段逻辑**；纯 ASCII 路径的 checkout 不要设置该属性（多 checkout 会争抢同一 build 目录、增量互相污染）。
- 单测用真实 `org.json` 依赖（android.jar 里是 stub，"not mocked"），勿删（`app/build.gradle.kts` 中 `libs.org.json`）。
- Release 签名走环境变量 `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`（无签名配置时仅 debug 可签），R8 minify 开启。APK 输出到 gitignored 的 `app/release/`。CI 用同名 secret 加 `KEYSTORE_BASE64`（见 workflow）。
- 版本号在 `app/build.gradle.kts` 的 `versionName`；`versionCode` 由公式 `10000*主 + 100*次 + 补丁` 自动推导。发版只改 versionName，提交信息用 `V1.6.1` 样式。

## 架构

- **无 DI 框架**：`BF1AdminApp.kt` 是手写 lazy 单例装配点（`EAApiService` / `CardToolApiService` / `CredentialManager` / 各 repository），新依赖照此模式加。
- **`CredentialManager`（`data/session/`）是唯一凭证生产者**：remid/sid 兑换、cookie 轮换落库、Battlelog sessionId 续期全部收口于此，消费方一律经过它。正确性模型：按 accountId 一把互斥锁（锁只覆盖单次兑换，绝不锁住卡服长循环）、锁内重读 DB 最新凭证、轮换仅增量合并（`mergeRotatedPersist`，没轮换不写库）。新增凭证相关逻辑必须走 CredentialManager，不要绕开它直接调 EA 兑换（会破坏轮换落库与账号级互斥）。
- 认证体系：**Juno (EA App) PKCE OAuth**（`JUNO_PC_CLIENT`，参数在 `data/remote/EAApiService.kt` 的 `JUNO_*` 常量）；ORIGIN_JS_SDK 隐式授权已废弃。`display=junoWeb%2Flogin` 是 cookie→auth_code 的桥接参数。登录三步：Juno code 换 access_token + refresh_token → 用 access_token + cookie 认证 → 播种 refresh_token（长期续期）。详细凭证流转见本地 `docs/EA-OAUTH-ARCHITECTURE.md`（未入库）。
- session 续期：`data/session/SessionRefreshScheduler` 用 WorkManager 每 6h 定时刷新（`SessionRefreshWorker`），距上次刷新不足间隔会跳过。
- Room (KSP) 存账号（AES/GCM 加密，`util/AccountCrypto.kt`）、服务器、session 缓存；改实体/DAO 需编译验证生成代码。
- `blaze/` 是自定义 Blaze 协议编解码（`BlazeCodec` / `BlazeSocket` / `BlazeClient`），卡服功能（`cardtool/CardToolService`）是长时循环 + 断线自动重连（authCode 重新兑换走 CredentialManager）。
- `data/remote/` 是 EA/Battlelog 网络层（OkHttp，单例连接池），`ui/` 是 Compose 界面（home / login / cardtool / admin）。

## 约定

- 代码注释与 commit message 用**中文**，conventional commits 风格：`feat(cardtool): ...`、`fix(login): ...`、`refactor(session): ...`、`test(...)`、`docs: ...`、`chore: ...`
- `docs/`（设计文档）、`.superpowers/`、`.references/`、`.tools/`、`.claude/` 全部 gitignored，仅本地工作文件，改动不必提交。
- `.env.txt` 严禁入库（含真实 EA 凭证）；`*.jks` / `*.keystore` / `*.p12` 已 gitignore。
