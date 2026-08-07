# EA OAuth 双体系认证与 Session 管理

> 背景: V1.6.0 引入 Juno (EA App) OAuth 流程后，系统同时存在 ORIGIN_JS_SDK 和 JUNO_PC_CLIENT 两套 OAuth 体系。本文档梳理它们的职责边界、remid/sid 的角色、以及凭证生命周期。

## 1. 两套 OAuth 体系

```
                         remid / sid (EA 账户登录态)
                       ┌──────────┴──────────┐
                       ▼                     ▼
               ORIGIN_JS_SDK            JUNO_PC_CLIENT
               (隐式授权)                (PKCE + pc_sign)
               response_type=token      response_type=code
                       │                     │
                       ▼                     ▼
                  access_token           access_token
                  (短期, ~分钟)           (JWS, ~1h)
                       │               + refresh_token
                       │               (长期, 可 rotation)
                       │                     │
            ┌──────────┼──────┐    ┌────────┼────────┐
            ▼          ▼      ▼    ▼        ▼        ▼
        pids/me/   getAuthCode  gateway   gateway   resolve
        personas   (sparta-     personas  personas  PID
                   backend)     (Juno     (Juno     (refresh
                   → sessionId  token)    cookie)   token)
                       │
                       ▼
              Sparta Gateway
              (Battlelog RPC)
```

| | ORIGIN_JS_SDK | JUNO_PC_CLIENT |
|---|---|---|
| OAuth 类型 | 隐式授权 (implicit) | PKCE (authorization_code) |
| 授权参数 | `response_type=token` | `response_type=code` |
| 安全机制 | 无 (client 公开) | pc_sign + code_challenge + client_secret |
| 产出 | access_token (短期) | access_token + refresh_token |
| access_token scope | 无 `dp.server.default` | `dp.server.default` ✅ |
| 用途 | Battlelog session 管理 | 查 EAID → PID |
| remid/sid 要求 | 必须来自 ORIGIN 流程 | 来自 Juno 流程 |
| 参考实现 | Origin 网页登录 | EA App 桌面客户端 |

## 2. remid / sid 的角色

### 2.1 本质

remid 和 sid 是 EA 账户级别的**长期会话 cookie**，不绑定任何特定 OAuth client。它们证明"当前 HTTP 会话属于哪个 EA 账户"。EA 的 `/connect/auth` 端点通过读取这个 cookie 来识别请求方。

### 2.2 EA 的 client 隔离

**EA 对不同 OAuth client_id 的 cookie 做了隔离**。通过哪个 client 产生的 remid/sid，原则上只能被同体系使用:

| cookie 来源 | ORIGIN_JS_SDK | JUNO_PC_CLIENT | sparta-backend + display=junoWeb |
|---|---|---|---|
| 浏览器手动复制 (ORIGIN 流程) | ✅ | ❌ | ✅ |
| Juno WebView (EA App 流程) | ❌ | ✅ | ✅ |
| EA App 桌面客户端 | ❌ | ✅ | ✅ |

**例外**: `sparta-backend-as-user-pc` + `display=junoWeb%2Flogin` 是 EA 提供的一个**桥接参数**——它告诉 Sparta Gateway "这个请求来自 EA App (Juno) 体系，请接受它的 cookie"。这就是 `getAuthCodeJuno()` 和 `CardToolApiService.getGatewaySession()` 能工作的原因。

### 2.3 在 AndroidTool 中的流转

```
登录:
  WebView (Juno PKCE) → CookieManager → 提取 remid/sid
  ManualLoginScreen → 用户粘贴 → remid/sid

存储:
  accounts.remid / accounts.sid (Room DB, AES/GCM 加密)
  session_cache.remidFingerprint (SHA-256, 用于验证 cookie 是否被替换)

轮换:
  每次 EA API 调用 → Set-Cookie 响应头 → accumulateRotatedCookies()
  → persistRotated() → 增量合并落库 (有则更新, 无则保留原值)

使用:
  EAApiService.getAccessToken()        → ORIGIN_JS_SDK 隐式授权
  EAApiService.getAuthCode()           → sparta-backend-as-user-pc (ORIGIN 体系)
  EAApiService.getAuthCodeJuno()       → sparta-backend + display=junoWeb (桥接)
  EAApiService.getAccessTokenJuno()    → JUNO_PC_CLIENT headless PKCE
  CardToolApiService.getGatewaySession() → sparta-backend + display=junoWeb (桥接)
  CardToolApiService.getBlazeAuthCode()  → GOS-BlazeServer-BFTUN-PC
```

## 3. 凭证生命周期

### 3.1 sessionId (Battlelog Gateway Session)

```
产生:
  authenticate() / authenticateWithJunoToken() → getAuthCode → getSessionId
  或 CardToolApiService.getGatewaySession()
  → JSON-RPC Authentication.getEnvIdViaAuthCode
  → sparta-gw.battlelog.com

存储:
  session_cache 表 (Room DB)
  ├─ encryptedSessionId (AES/GCM)
  ├─ remidFingerprint (SHA-256, 验证 remid 未被替换)
  └─ refreshedAt (epoch ms)

续期:
  SessionRefreshScheduler (WorkManager, 每 6h)
  → SessionRefreshWorker
  → CredentialManager.refreshActiveSession()
    ├─ 缓存有效 (<12h + remid 指纹匹配) → 直接返回 (0 HTTP)
    └─ 过期 → refreshSessionIdLocked()
         └─ getAccessToken(ORIGIN_JS_SDK) → auth_code → sessionId
         ⚠️ Juno 账号: getAccessToken 返回 login_required

读取:
  CredentialManager.getActiveSessionId()
  → cachedOrRefreshLocked()
  → 返回 sessionId

消费:
  以 X-GatewaySession header 发送到 Sparta Gateway JSON-RPC:
  - GameServer.getFullServerDetails
  - RSP.getServerDetails / addServerAdmin / removeServerAdmin
  - Onboarding.welcomeMessage

过期后:
  gateway 返回 -32501/-32504 → invalidateActiveSession() → 重兑一次
  ORIGIN_JS_SDK 返回 login_required → CredentialsExpiredException → 用户重登
```

### 3.2 refresh_token (Juno OAuth)

```
产生:
  WebView 登录 → qrc:// 回调拿到 code
  → exchangeJunoCode(code, codeVerifier)
  → POST /connect/token (grant_type=authorization_code)
  → 返回 access_token + refresh_token

存储:
  accounts.junoRefreshToken (Room DB, AES/GCM 加密, nullable)
  null = 未播种 (手动输入登录的账号没有此字段)

续期 (rotation):
  CredentialManager.resolvePlayerName() priority 1:
  → refreshJunoAccessToken(refreshToken)
  → POST /connect/token (grant_type=refresh_token)
  → 返回新 access_token + 新 refresh_token
  → saveJunoRefreshToken() 落库新值 (EA rotation)
  → 失败 → CredentialsExpiredException → 清 null → 降级 priority 2

消费:
  resolvePlayerNameByJunoToken(accessToken, eaid)
  → GET gateway.ea.com/proxy/identity/personas
  → Authorization: Bearer <access_token>
  → 解析 personaId

注意:
  只在 PID 查询时使用，不参与 sessionId 管理。
  EAappEmulater 用 refresh_token 管理所有 EA API 访问;
  AndroidTool 只用它查 PID，session 管理仍走 remid/sid。
```

### 3.3 remid / sid (EA Account Session Cookies)

```
产生:
  WebView 登录 → EA 在 WebView 的 CookieManager 中下发
  或浏览器访问 ea.com → 开发者工具复制

存储:
  accounts.remid / accounts.sid (AES/GCM 加密)

轮换:
  每次 EA API 响应中 Set-Cookie 可能包含新 remid/sid
  → accumulateRotatedCookies() 增量累积
  → persistRotated() 增量落库
  原则: 有则更新, 无则保留原值; 空值(Max-Age=0)忽略

过期:
  ORIGIN_JS_SDK 返回 login_required → CredentialsExpiredException
  CardTool checkOAuthRedirect: fid= / login_require
  → 提示用户重新登录
```

## 4. EAappEmulater 的做法

EAappEmulater 模拟 EA App 桌面客户端，全程只使用 **JUNO_PC_CLIENT** 体系。

```
  不做的事:
  - 不使用 ORIGIN_JS_SDK client
  - 不访问 Sparta Gateway (sparta-gw.battlelog.com)
  - 不获取 Battlelog sessionId
  - 不需要手动输入 remid/sid

  做的事:
  1. WebView2 → Juno PKCE 登录 → code
  2. exchange code → access_token + refresh_token
  3. CookieManager 提取 remid/sid (仅用于储存在 ini)
  4. 所有 EA API 调用 → Bearer Juno access_token
  5. refresh_token + remidHash 绑定 → 静默登录

  静默登录 (TrySilentLoginAsync):
  - 有 refresh_token + remidHash 匹配 → refresh 换 access_token
  - 没有 → cookie GET auth URL → 302 → qrc:// code → exchange
  - SemaphoreSlim(1,1) 串行化所有 Juno 操作
  - 10 分钟 session 超时 + 单次完成 guard

  本质差异:
  EAappEmulater 只需要 Juno token —— 它管理 EA App 桌面客户端状态。
  AndroidTool 需要两套 —— OriginsessionId 管 Battlelog 服务器, Juno token 查 PID。
```

## 5. 当前已知缺口

### 5.1 12h 后 Juno 账号 session 续期失败

```
  SessionRefreshScheduler (6h 定时)
  → refreshSessionIdLocked()
  → getAccessToken(ORIGIN_JS_SDK)
  → Juno cookie → login_required → CredentialsExpiredException
  → SessionRefreshWorker 静默吞掉
  → 12h 缓存过期后, 下次业务调用 → 提示重新登录
```

**修复方向** (待实现):
- `refreshSessionIdLocked` 检测 `junoRefreshToken != null`
- → `refreshJunoAccessToken` → Juno access_token
- → `getAuthCodeJuno(display=junoWeb%2Flogin)` → auth_code
- → `getSessionId` → 新 sessionId
- → `saveSession` 落库

### 5.2 codeVerifier 生命周期

`codeVerifier` 只存在 `LoginViewModel.junoAuthParams` (ViewModel 内存)。如果 ViewModel 在登录过程中被重建（如系统回收），code exchange 会失败（表现为 "refresh_token 播种失败" 非阻塞消息，PID 查询退化为 gametools）。

### 5.3 PC_SIGN 固定假硬件值

`JunoSigner.buildPcSign` 使用固定假硬件值（board/bios/disk serial = "None", osInstallDate = 1970, gpuId = 0）。EAappEmulater 先用 WMI 读真实硬件（3s 超时），失败才降级到固定值。如果 EA 加强 pc_sign 校验，固定值可能被识别为异常。

## 6. 调用路径总览

```
  ┌─ 手动输入登录 ─────────────────────────────────────┐
  │  remid/sid (用户粘贴)                                │
  │  → loginWithCookies()                               │
  │  → authenticate(remid, sid)                         │
  │  → getAccessToken(ORIGIN_JS_SDK) → access_token     │
  │  → getPersonaInfo → getAuthCode → getSessionId      │
  └─────────────────────────────────────────────────────┘

  ┌─ Juno WebView 登录 ────────────────────────────────┐
  │  WebView → Juno PKCE → code + remid/sid             │
  │  → exchangeJunoCode(code) → access_token             │
  │  → authenticateWithJunoToken(access_token, remid, sid)│
  │  → getPersonaInfo(access_token)                     │
  │  → getAuthCodeJuno(display=junoWeb) → getSessionId  │
  │  → saveJunoRefreshToken(refresh_token)               │
  └─────────────────────────────────────────────────────┘

  ┌─ Session 续期 (后台 6h) ───────────────────────────┐
  │  cachedOrRefreshLocked()                             │
  │  ├─ 缓存有效 (<12h) → 直接返回                       │
  │  └─ 过期 → refreshSessionIdLocked()                  │
  │       └─ getAccessToken(ORIGIN_JS_SDK)  ⚠️          │
  └─────────────────────────────────────────────────────┘

  ┌─ PID 查询 ─────────────────────────────────────────┐
  │  resolvePlayerName(playerName)                       │
  │  ├─ priority 1: Juno refresh_token                   │
  │  │  → refreshJunoAccessToken (rotation + persist)    │
  │  │  → resolvePlayerNameByJunoToken (Bearer)          │
  │  ├─ priority 2: ORIGIN cookie → 403 → Juno cookie    │
  │  │  → getAccessToken(ORIGIN_JS_SDK)                  │
  │  │  → 403 insufficient_scope                         │
  │  │  → getAccessTokenJuno(cookie PKCE)                │
  │  │  → queryPersonas                                  │
  │  └─ priority 3: gametools API                        │
  └─────────────────────────────────────────────────────┘
```

## 7. 关键文件索引

| 文件 | 职责 |
|---|---|
| `data/remote/EAApiService.kt` | OAuth 端点: authenticate, authenticateWithJunoToken, exchangeJunoCode, refreshJunoAccessToken, resolvePlayerNameByEAID, getAccessToken, getAuthCode, getAuthCodeJuno |
| `data/remote/JunoSigner.kt` | pc_sign 生成 (FNV-1a64 + HMAC-SHA256) |
| `data/remote/RotatedCookies.kt` | cookie 轮换累积与增量合并 |
| `data/session/CredentialManager.kt` | 单一凭证生产者: 账号锁, session 缓存, cookie 轮换落库 |
| `data/session/SessionRefreshScheduler.kt` | WorkManager 6h 定时续期调度 |
| `data/session/SessionRefreshWorker.kt` | 后台续期执行器 |
| `data/session/SessionCachePolicy.kt` | session 缓存有效期常量 (6h refresh / 12h max) |
| `data/repository/AccountRepository.kt` | 账号 CRUD + remid/sid/junoRefreshToken 加密存储 |
| `ui/login/WebViewLoginScreen.kt` | WebView Juno 登录 UI, cookie 提取, URL 拦截 |
| `ui/login/LoginViewModel.kt` | 登录逻辑: onJunoWebViewLogin, loginWithCookies |
| `ui/login/ManualLoginScreen.kt` | 手动 remid/sid 输入 |
| `data/remote/CardToolApiService.kt` | CardTool gateway (Blaze authCode, Sparta sessionId) |
| `util/CookieHelper.kt` | remid/sid 解析 |
| `util/AccountCrypto.kt` | AES/GCM 加解密 (AndroidKeyStore) |

---

*最后更新: 2026-08-07 (V1.6.0 bug fix 期间)*
