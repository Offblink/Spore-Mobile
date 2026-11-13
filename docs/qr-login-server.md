# Spore-Cloud 扫码登录 · 服务端改造说明

> 客户端版本：Spore-Mobile v1.7+（2026-11 重构：删除桌面扫码配对与账号口令直连，统一为扫码登录）。
> 目标：手机扫服务器页面上的一张二维码，换到与现有同步协议完全兼容的 JWT。

## 1. 流程

```text
服务器登录页（浏览器）
  │ 1. POST /api/auth/qr/ticket        （已登录的 web 会话 / 桌面端）
  │    ← { code:0, data:{ ticket, expiresIn } }
  │ 2. 页面把 {v:1, api, ticket} 渲染成二维码
  ▼
手机 Spore-Mobile
  │ 3. 扫码解析 api + ticket
  │ 4. POST {api}/auth/qr/exchange     body: {"ticket":"..."}
  │    ← { code:0, data:{ token, user:{id,username,nickname} } }
  │ 5. GET {api}/users/me             Authorization: Bearer <token>
  │    ← { code:0, data:{ id, username, nickname, ... } }
  │ 6. token 存本机 prefs；同步双游标清零，下轮全量交换
  ▼
现有 SyncEngine（协议不变）
```

二维码只承载「去哪台服务器 + 一次性票据」，**不含长期凭据**。票据被扫走也只能换一次 token，且很快过期。

## 2. 二维码载荷

JSON（推荐，手机端按 `raw.startsWith("{")` 解析）：

```json
{"v":1,"api":"http://192.168.1.5:19080","ticket":"qrl_7f3c9a2e5b1d4c60"}
```

兼容格式（手机端也认）：

```text
spore://login?api=http%3A%2F%2F192.168.1.5%3A19080&ticket=qrl_7f3c9a2e5b1d4c60
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | 否 | 协议版本，当前 `1`；客户端忽略未知版本 |
| `api` | 是 | 网关基址。可写 `192.168.1.5:19080`、`http://...`、带不带 `/api` 都行（客户端自动归一） |
| `ticket` | 是 | 一次性登录票据，服务端生成；建议 ≥ 24 字符随机串 |

注意：
- `api` 必须是**手机能访问到的地址**（局域网 IP / 域名），不要下发 `127.0.0.1` 或容器内网地址。
- 二维码尺寸建议 ≥ 240px，纠错等级 M；有效期与票据一致（默认 60s），页面到点自动刷新。

## 3. 接口契约

现有客户端要求所有响应走统一 R 包装（`SyncClient` 的约定）：

```json
{"code":0,"message":"ok","data":{ ... }}
```

`code != 0` 时客户端把 `message` 直接展示给用户。

### 3.1 申请票据

```http
POST /api/auth/qr/ticket
Authorization: Bearer <web/桌面端 JWT>
```

响应：

```json
{"code":0,"data":{"ticket":"qrl_7f3c9a2e5b1d4c60","expiresIn":60}}
```

- 必须要求已登录身份；票据与本用户绑定。
- 同一用户可同时存在多张未使用票据，但建议限制为 3 张（防刷）。
- 每次刷新二维码就重新申请一张（旧票据自然过期）。

### 3.2 用票据换 token

```http
POST /api/auth/qr/exchange
Content-Type: application/json

{"ticket":"qrl_7f3c9a2e5b1d4c60"}
```

无需 Authorization。响应：

```json
{"code":0,"data":{
  "token":"<与 /auth/login 同格式的 JWT>",
  "user":{"id":1001,"username":"demo","nickname":"演示账号"}
}}
```

要点：
- **原子消费**：`UPDATE qr_login_ticket SET used_at = NOW() WHERE ticket_hash = ? AND used_at IS NULL AND expires_at > NOW()`，影响行数为 0 就返回失败（防并发重复换）。
- `token` 必须与现有登录签发的 JWT 完全同构（同一 secret / 有效期策略），否则同步接口不认。
- `user` 字段可省略；客户端随后还会调 `/users/me` 做连通校验。

### 3.3 用户信息（已有，保持）

```http
GET /api/users/me
Authorization: Bearer <token>
```

客户端至少读取 `id`、`username`、`nickname`（缺 nickname 时回落 username）。

## 4. 存储设计

MySQL 示例：

```sql
CREATE TABLE qr_login_ticket (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_hash  CHAR(64)    NOT NULL,          -- SHA-256(ticket)，不存明文
  user_id      BIGINT      NOT NULL,
  expires_at   DATETIME    NOT NULL,
  used_at      DATETIME    NULL,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ticket_hash (ticket_hash),
  KEY idx_expires (expires_at)
);
```

- 只存 `ticket` 的哈希；生成时 `SecureRandom` 32 字节 → Base64URL（或 `qrl_` + 24 字符）。
- 定时任务清理 `expires_at < NOW() - INTERVAL 1 DAY` 的行。
- 也可用 Redis：`SET qr:{hash} {uid} EX 60 NX`，交换时 `GETDEL` 原子消费。

## 5. Spring Boot 参考实现

```java
@RestController
@RequestMapping("/api/auth/qr")
public class QrLoginController {

    private final QrTicketService tickets;   // 自己实现：issue(userId) / consume(hash) -> userId
    private final JwtService jwt;

    @PostMapping("/ticket")
    public R<TicketVO> issue(@AuthenticationPrincipal LoginUser me) {
        String ticket = tickets.issue(me.getId());     // 明文只返回这一次
        return R.ok(new TicketVO(ticket, 60));
    }

    @PostMapping("/exchange")
    public R<ExchangeVO> exchange(@RequestBody ExchangeDTO body) {
        Long uid = tickets.consume(sha256(body.getTicket()));  // 一次性 + 过期校验
        if (uid == null) {
            return R.fail(40101, "二维码已过期，请刷新后重试");
        }
        String token = jwt.sign(uid);                  // 与登录同一签发逻辑
        return R.ok(new ExchangeVO(token, userService.brief(uid)));
    }
}
```

`sha256` 用 `MessageDigest`；`consume` 里做上面那条原子的 `UPDATE`。

## 6. 安全清单

- 生产环境必须 HTTPS；局域网 HTTP 仅限课设/内网。
- 票据 **单次使用** + TTL（≤ 120s）；重复使用返回明确错误。
- 日志里禁止打印 `ticket` 明文与完整 token（客户端也已遵守）。
- 申请接口限流（如每用户 10 次/分钟、每 IP 60 次/分钟）。
- 二维码页面加 `Cache-Control: no-store`，避免浏览器历史/代理缓存票据。
- 可选：`ticket` 绑定申请时的客户端 IP 段，交换时校验同一网段（手机与浏览器常在同一个 Wi-Fi）。

## 7. 联调与验收

```bash
# 1. 申请票据（用 web 登录拿到的 token）
curl -s -X POST http://192.168.1.5:19080/api/auth/qr/ticket \
  -H "Authorization: Bearer $WEB_TOKEN"

# 2. 换 token（模拟手机）
curl -s -X POST http://192.168.1.5:19080/api/auth/qr/exchange \
  -H "Content-Type: application/json" \
  -d '{"ticket":"qrl_xxx"}'

# 3. 再次使用同一票据 → 必须失败
curl -s -X POST http://192.168.1.5:19080/api/auth/qr/exchange \
  -H "Content-Type: application/json" \
  -d '{"ticket":"qrl_xxx"}'

# 4. 用换到的 token 校验用户
curl -s http://192.168.1.5:19080/api/users/me \
  -H "Authorization: Bearer $TOKEN"
```

手机端验收：
1. 设置 → 服务器与同步 → 扫码登录 → 扫页面二维码。
2. 成功后卡片显示「已登录 · 昵称 + 地址」，「立即同步」可用。
3. 同步一轮后 `pushCursor/pullCursor` 前进；再扫码换账号会清零双游标、下轮全量交换。

## 8. 客户端落点（便于排查）

| 位置 | 作用 |
|---|---|
| `QrLoginActivity` | CameraX + ML Kit 扫 QR；解析 `{api,ticket}` 或 `spore://login` |
| `sync/ServerConnector.exchangeQr()` | `POST /auth/qr/exchange` → `/users/me` → 写 `SporeSyncState` |
| `SettingsActivity` 桥 `qrLogin/pairInfo/syncStart/unpair` | 设置页与原生交互 |
| `SporeSyncState` | `api/token/nick/uid` + 双游标，换服务器清零 |

失败提示直接透传服务端 `message`；建议写人话，例如：
- `40101 二维码已过期，请刷新后重试`
- `40102 该二维码已被使用`
- `40301 当前账号无权登录该服务器`
