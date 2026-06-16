# P0 — Security Model Correction Design

- 日期: 2026-06-17
- 性质: 安全模型重设计(SignatureGuard 信任机制 + 调用方身份)
- 前提: 有第三方/不同签名 App 接入(已确认);同版本部署

## 背景与问题

`SignatureGuard.verify` 当前逻辑:
```
if (callingUid != selfUid) return false
// 且要求 getPackagesForUid(callingUid) 的每个包签名 == 自身签名
```
即**只有同 UID / 同签名的调用方能通过**。这与"有第三方接入"直接冲突 —— 第三方 App(不同签名、不同 UID)会被 `onBind` 和每次 `invoke` 拒绝。框架在真实场景下无法工作。这是方向性错误,必须修(不是删)。

同时,权限判定使用的调用方身份是"进程名"(经 `CallerResolver` 由 PID 解析),进程名不可信且解析昂贵。

## 设计目标

1. 第三方可通过**可信签名白名单**接入,自身签名始终可信。
2. 权限 allow/deny 以**包名**为身份键,身份来源为平台可信的 `Binder.getCallingUid()`。
3. 安全默认 fail-closed:未配置白名单时仅自身签名通过。
4. 核心信任判定可纯 JVM 单测。

## 设计

### 1. SignatureGuard 重设计

新 `verify(context, callingUid): Boolean`:
- 可信签名集合 = `{ selfSignatureHash } ∪ config.security.trustedSignatures`。
- 取 `getPackagesForUid(callingUid)`;若为 null → false。
- 调用方**每个包**的签名哈希都必须落在可信集合内 → true,否则 false。
- 移除 `callingUid != selfUid` 硬拒(自身仍通过,因自身签名在集合内)。
- 保留 per-UID 结果缓存(`ConcurrentHashMap<Int, Boolean>`)。

抽出纯函数便于单测:
```kotlin
// 给定调用方各包的签名哈希集合与可信集合,判定是否全部可信
fun isTrusted(callerSignatureHashes: Set<String>, trusted: Set<String>): Boolean =
    callerSignatureHashes.isNotEmpty() && trusted.containsAll(callerSignatureHashes)
```
`verify` 负责从 PackageManager 取每个包的签名哈希(沿用现有 `computeSignatureHash`),组装 `callerSignatureHashes`,再调 `isTrusted`。

构造依赖:`SignatureGuard` 需要访问 `config.security.trustedSignatures`。通过 `init(context, trustedSignatures: Set<String>)` 传入(FalconManager 调用时传 `config.security.trustedSignatures`),内部保存为字段。

### 2. 配置

`FalconConfig.kt` 的 `SecurityConfig` 增加:
```kotlin
var trustedSignatures: Set<String> = emptySet()  // 可信签名证书 SHA-256(hex)
```
默认空 → 可信集合仅含自身签名 → fail-closed。第三方需显式 pin。

### 3. 调用方身份:进程名 → 包名

`CallerResolver` 从 PID→进程名 改为 UID→包名:
```kotlin
class CallerResolver(private val context: Context) {
    private val cache = ConcurrentHashMap<Int, String>()
    fun resolve(uid: Int): String {
        cache[uid]?.let { return it }
        val name = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        cache[uid] = name
        return name
    }
}
```

`IpcHostService.invoke`:
```kotlin
val callingUid = Binder.getCallingUid()
val callingPid = Binder.getCallingPid()
if (!signatureGuard.verify(this@IpcHostService, callingUid)) {
    return IpcEnvelope.error(ErrorCode.UNAUTHORIZED, "Untrusted signature")
}
val callerPackage = callerResolver.resolve(callingUid)
val result = messageRouter.handleLocal(request, callerPackage, callingPid)
```

`MessageRouter.handleLocal` 第二参数语义由 callerProcess 改为 callerPackage(纯改名,逻辑不变)。`PermissionChecker.check(serviceKey, callerPackage)` 语义不变 —— allow/deny 名单现以包名为键。`AccessRule` 文档说明键为包名。`defaultAllow` 保持现状(签名是信任门,per-service 规则做细化),可配置。

### 4. 受影响文件

- `falcon-core/src/main/java/com/falcon/ipc/security/SignatureGuard.kt`(重写 verify + isTrusted + init 带 trustedSignatures)
- `falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt`(SecurityConfig.trustedSignatures)
- `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt`(init 传 trustedSignatures)
- `falcon-core/src/main/java/com/falcon/ipc/util/CallerResolver.kt`(UID→包名)
- `falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt`(用 UID 解析包名)
- `falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt`(参数改名 callerProcess→callerPackage)
- `CLAUDE.md`(更新签名模型描述)

### 5. 测试

- `SignatureGuardTest`:为 `isTrusted` 纯函数加单测 —— 自身命中、白名单命中、未命中、空调用方签名、多包混合(一包不可信则整体拒)。现有 `verify` 测试(Robolectric/Mockito)按新签名调整。
- `PermissionCheckerTest`:已有;身份键改名不改逻辑,确认仍通过。
- `MessageRouterTest`:`handleLocal` 第二参数改名,更新调用处(语义不变)。
- CallerResolver 的 PackageManager 路径:Android 依赖,靠审查 + 现有集成结构。

## 安全默认汇总

- 无 `trustedSignatures` 配置 → 仅自身签名通过(fail-closed,不破坏现有同签名用法)。
- 第三方 → 必须在 `trustedSignatures` 显式 pin 其证书 SHA-256。
- 身份来源:`Binder.getCallingUid()`(平台可信),非进程名;包名经 UID 解析。

## 风险

- SignatureGuard 改动是真实安全边界,需安全评审;务必保持 fail-closed 默认。
- per-UID 缓存:UID 复用极端情况下可能缓存陈旧结果;签名验证按 UID 缓存在 Android 同会话内可接受(UID 在进程存活期稳定)。
- 若后续 minSdk 提升到 31+,可考虑迁移到平台 `knownSigner` 权限,届时本设计可作为兜底。
