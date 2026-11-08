# REST API 文档

插件提供三组接口：

| 分组 | 前缀 | 权限 |
| --- | --- | --- |
| 公共 API | `/apis/api.reward.nanzhiy.cn/v1alpha1` | 匿名可访问 |
| 管理 API | `/apis/console.api.reward.nanzhiy.cn/v1alpha1` | 需要「打赏记录管理」权限 |
| 标准 CRUD | `/apis/reward.nanzhiy.cn/v1alpha1` | 由 Halo 自动生成，需要对应权限 |

---

## 公共 API（匿名可访问）

### 端点列表

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/public/config` | 页面配置（已脱敏） |
| GET | `/public/wall` | 赞助者墙分页 |
| GET | `/public/stats` | 累计与本月统计 |
| GET | `/public/captcha` | 获取图形验证码 |
| POST | `/records` | 提交打赏记录 |
| POST | `/afdian/webhook` | 爱发电订单回调（供平台调用） |

所有响应均不包含联系方式、交易单号等后台字段。

公共接口按来源 IP 独立限流：

| 接口 | 配额 |
| --- | --- |
| `POST /records` | 每 IP 10 分钟 5 次 |
| `GET /public/captcha` | 每 IP 1 分钟 10 次 |
| `GET /public/config` | 每 IP 1 分钟 60 次 |
| `GET /public/wall` | 每 IP 1 分钟 120 次 |
| `GET /public/stats` | 每 IP 1 分钟 60 次 |

「启用打赏页面」开关只影响 `/reward` 页面、`GET /public/config`、`GET /public/captcha` 与 `POST /records`；`GET /public/wall` 与 `GET /public/stats` 不受该开关影响，便于主题在其它页面展示数据。

### 获取页面配置

```
GET /apis/api.reward.nanzhiy.cn/v1alpha1/public/config
```

```json
{
  "title": "打赏支持",
  "intro": "您的打赏将用于服务器维护、内容创作和功能开发…",
  "currencySymbol": "¥",
  "wechatQr": "/upload/wechat.png",
  "alipayQr": "/upload/alipay.png",
  "paymentTip": "扫码后请手动输入金额…",
  "afdianPageUrl": "https://afdian.com/a/yourname",
  "enableRecordSubmit": true,
  "requireLogin": false,
  "allowMessage": true,
  "wallEnabled": true,
  "enableCaptcha": true,
  "enableTransactionNo": false,
  "enableAvatar": false,
  "loggedIn": false
}
```

`loggedIn` 用于前台判断是否允许提交（配合「仅登录用户可提交」开关）。

### 获取赞助者墙

```
GET /apis/api.reward.nanzhiy.cn/v1alpha1/public/wall?page=1&size=10
```

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `page` | int | 页码，从 1 开始 |
| `size` | int | 每页条数，1 ~ 50；不传时取插件设置中的「赞助者墙每页条数」 |

```json
{
  "items": [
    {
      "nickname": "张三",
      "initial": "张",
      "amount": 18.00,
      "avatar": "https://cdn.example.com/a.png",
      "message": "文章很有帮助",
      "createdAt": "2026-10-31"
    }
  ],
  "total": 12,
  "page": 1,
  "size": 10
}
```

- 只返回状态为「已确认」的记录
- `amount` 为 `null` 表示后台关闭了「墙面显示金额」
- `avatar`、`message` 为 `null` 表示访客未填写

### 获取统计

```
GET /apis/api.reward.nanzhiy.cn/v1alpha1/public/stats
```

> 统计结果在服务端缓存 60 秒，写操作后缓存立即失效。
>
> 金额字段不受「在赞助者墙显示金额」开关控制，且接口匿名可访问；不需要公开收入数据时，请不要在主题中调用它。

```json
{
  "totalCount": 12,
  "monthCount": 3,
  "totalAmount": 234.00,
  "monthAmount": 54.00
}
```

### 获取图形验证码

```
GET /apis/api.reward.nanzhiy.cn/v1alpha1/public/captcha
```

```json
{
  "id": "8f3c9a2e-5b1d-4c6a-9f30-2a7e5d8c1b44",
  "image": "data:image/png;base64,iVBORw0KGgo..."
}
```

| 规则 | 说明 |
| --- | --- |
| 有效期 | 5 分钟 |
| 使用次数 | 一次性，校验后立即失效 |
| 大小写 | 不区分 |
| 生成限流 | 每 IP 每分钟最多 10 张，超出返回 `429` |
| 总开关 | 「启用打赏页面」关闭时直接返回 `404`，不消耗限流配额 |

### 提交打赏记录

```
POST /apis/api.reward.nanzhiy.cn/v1alpha1/records
Content-Type: application/json
```

```json
{
  "amount": 18,
  "nickname": "张三",
  "method": "wechat",
  "message": "加油",
  "contact": "zhangsan@qq.com",
  "transactionNo": "4200001234202610311234567890",
  "avatar": "https://cdn.example.com/avatar.png",
  "captchaId": "8f3c9a2e-5b1d-4c6a-9f30-2a7e5d8c1b44",
  "captchaCode": "A3KP"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `amount` | 是 | 金额，必须大于 0 |
| `nickname` | 是 | 昵称，最长 30 |
| `method` | 否 | `wechat` 或 `alipay`，默认 `wechat` |
| `message` | 否 | 留言，最长 200，仅当后台开启留言时保存 |
| `contact` | 否 | 联系方式，最长 100，仅后台可见 |
| `transactionNo` | 条件 | 后台开启「要求填写交易单号」时必填，最长 64 |
| `avatar` | 否 | 头像地址，最长 500，仅当后台开启头像时保存 |
| `captchaId` / `captchaCode` | 条件 | 后台开启验证码时必填 |

打赏时间由服务端按提交时刻写入，**不接受访客指定**，避免把记录伪造成历史日期；需要补录历史打赏请走后台管理接口。

成功响应：

```json
{
  "status": "PENDING",
  "message": "已提交，等待站长确认后展示"
}
```

若后台开启了「提交后自动通过」，`status` 为 `CONFIRMED`。

### 错误响应

公开接口的业务错误统一返回 `message` 字段：

```json
{
  "message": "验证码错误或已过期"
}
```

| 状态码 | 场景 |
| --- | --- |
| 400 | 参数校验失败（金额非法、昵称过长、验证码错误、未登录提交、请求体不是合法 JSON 等） |
| 429 | 提交过于频繁（每 IP 10 分钟 5 次）或验证码获取过于频繁（每 IP 1 分钟 10 次） |
| 500 | 服务端异常（写库失败、验证码渲染失败等），`message` 为通用提示 |

### 爱发电回调

```
POST /apis/api.reward.nanzhiy.cn/v1alpha1/afdian/webhook
```

供爱发电平台调用，固定返回：

```json
{ "ec": 200, "em": "" }
```

该路径需要匿名可访问：Halo 会把「有名称段的 POST」按非资源请求处理，只认 `nonResourceURLs` 规则，插件已在匿名角色中显式放行该路径（`extensions/role-templates.yaml`）。若站点前置了 CDN / WAF，还需把该路径加入白名单。

处理逻辑：

1. 立即返回成功结构（未返回时平台判定为失败）
2. 异步调用 `query-order` 反查订单，确认单号一致、真实存在且 `status = 2`
3. 以 `afdian-{订单号}` 为记录名入库，天然幂等
4. 请求体按原始字节解析，不依赖 `Content-Type`；结构异常、单号查不到、未支付、凭据缺失都会记一条 `warn` 日志，且不会入库
5. 平台收到成功响应后不会重推，漏掉的订单需在后台点「同步爱发电」补齐

---

## 管理 API（需要认证）

前缀 `/apis/console.api.reward.nanzhiy.cn/v1alpha1`。列表需要「打赏记录查看」权限（`plugin-reward-role-view`）；新增、编辑、批量变更、删除、同步与导出需要「打赏记录管理」权限（`plugin-reward-role-manage`）。

这些接口主要服务于插件自带的后台页面，**不承诺对外兼容**，字段与方法可能随版本调整。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/records?page=1&size=20&status=PENDING&keyword=张` | 列表，支持状态筛选与关键词搜索 |
| POST | `/records` | 后台手动新增，`rewardTime` 可指定打赏时间，留空取当前时间 |
| PUT | `/records/{name}` | 编辑记录，`rewardTime` 可改；不传表示不修改 |
| PUT | `/records/{name}/status` | 单条改状态 |
| PUT | `/records/batch-status` | 批量改状态 |
| PUT | `/records/batch-delete` | 批量删除 |
| DELETE | `/records/{name}` | 删除单条 |
| GET | `/records/export?status=PENDING&keyword=张` | 导出 CSV，首列为打赏时间（未填写时为入库时间，服务器时区），字段以 `'` 前缀保护以免被表格软件当作公式执行；筛选参数与列表一致，单次最多导出 20000 条 |
| GET | `/afdian/ping` | 检测爱发电凭据 |
| PUT | `/afdian/sync` | 手动同步爱发电订单 |

`status` 取值：`PENDING`、`CONFIRMED`、`REJECTED`。

金额上限 `1000000`，最多两位小数；字段超长按原始值校验并返回 `400`，不会静默截断。

> 带名称段的接口（`/records/{name}/status`、`/records/batch-status`、`/records/batch-delete`、`/afdian/sync`）使用 PUT 而不是 POST：Halo 会把「有名称段的 POST」降级为非资源请求，此时资源型权限规则不生效，非超级管理员会被拒绝。

`keyword` 会匹配昵称、留言、交易单号三个字段。

---

## 标准 CRUD 端点

Halo 会为自定义模型自动生成标准端点：

```
GET    /apis/reward.nanzhiy.cn/v1alpha1/records
GET    /apis/reward.nanzhiy.cn/v1alpha1/records/{name}
POST   /apis/reward.nanzhiy.cn/v1alpha1/records
PUT    /apis/reward.nanzhiy.cn/v1alpha1/records/{name}
DELETE /apis/reward.nanzhiy.cn/v1alpha1/records/{name}
```

使用这些端点需要相应的 RBAC 权限，且请求体必须是完整的 Kubernetes 风格资源对象（含 `apiVersion`、`kind`、`metadata`、`spec`）。

一般场景建议使用上面的管理 API，字段更简单。

---

## 相关文档

- [主题 API 文档](./theme-api.md) — 模板变量、Finder API、模态框引用
