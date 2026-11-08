# AGENTS.md

面向 AI 协作者的开发约定。动手前先读「0. 硬规则」「3. 注释规范」「5. 架构契约」，再读改动范围对应的小节。

## 0. 硬规则

1. **禁止修改版本号**：`gradle.properties` 的 `version` 与 `src/main/resources/plugin.yaml` 的 `spec.version` 由维护者按发布节奏手工提升，AI 不得改动，也不得顺手「对齐」成别的值。两处必须保持一致。
2. **不私自 commit / push**：只有用户明确说「提交」「推送」时才执行，且必须单独执行、先列改动范围。禁止 force push，禁止 amend / rebase 已推送的提交。不要把 `git add && git commit && git push` 串成一条命令。
3. **先读后改**：改任何文件前先读当前内容与调用方；跨文件契约（配置字段、记录字段、权限、模板变量）必须同步改齐，不能只改一处。
4. **注释按「3. 注释规范」**：只描述「当前代码为什么是这样」，不写日期、不写「发现问题 → 修复」的过程叙事。
5. **口径不确定先问用户**：业务语义、字段增删、默认值、公开行为、权限边界、UI 文案，决定权都在用户，不在 AI。
6. **提交规范见「4. 提交规范」**。

## 1. 快速上手

Halo 打赏插件（`plugin-reward`）：前台提供 `/reward` 页面，访客扫码打赏后可提交记录，站长在后台确认后上「赞助者墙」；可选对接爱发电，订单经开放平台反查后入库。

技术栈：Java 21 + Spring WebFlux（Halo 插件运行在 Halo 的 JVM 内）+ Vue 3 / TypeScript / Vite（控制台 UI，经 `@halo-dev/ui-plugin-bundler-kit` 打包）。最低 Halo 版本见 `plugin.yaml` 的 `requires`。

| 命令 | 作用 |
| --- | --- |
| `./gradlew clean build` | 完整构建：先 `pnpm install` + `pnpm build` 出控制台 UI，再打包为 `build/libs/plugin-reward-<version>.jar` |
| `./gradlew compileJava` | 只编译后端，改 Java 时快速验证 |
| `./gradlew haloServer` | 用 DevTools 起本地 Halo（需 Docker），默认 <http://localhost:8090/console> |
| `./gradlew reload` / `watch` | 热加载插件 / 监听变更 |
| `pnpm --dir ui build` | 只构建控制台 UI（含 `vue-tsc` 类型检查） |
| `pnpm --dir ui dev` | UI 产物 watch 模式 |

改动后至少跑通 `./gradlew clean build`；只改前台脚本/模板时也要跑，确认资源确实进了 JAR。

## 2. 目录结构速览

- `src/main/java/cn/nanzhiy/reward/`
  - `RewardPlugin.java` — 插件生命周期，注册 `RewardRecord` 与索引；插件名常量 `NAME`
  - `afdian/` — 爱发电客户端（签名/双域名回退）与订单服务
  - `config/` — 设置模型，字段名必须与 `extensions/settings.yaml` 的 `name` 一一对应
  - `endpoint/` — 公开接口、后台接口、爱发电回调
  - `extension/` — `RewardRecord` 自定义模型（GVK）
  - `finders/` — 主题侧 Finder（`rewardFinder`）
  - `router/` — `/reward` 页面路由
  - `service/` — 记录读写、配置读取、通知
  - `support/` — 图形验证码、内存限流、字段清洗
  - `vo/` — 对外视图对象
- `src/main/resources/`
  - `plugin.yaml` — 清单，**`spec.version` 禁止修改**
  - `extensions/` — 设置表单、角色模板、通知模板、静态资源反向代理
  - `templates/` — 主题侧 Thymeleaf 模板（`reward.html` 页面、`reward-modal.html` 按钮片段）
  - `static/dist/` — 主题侧**手写**前台脚本与样式（非构建产物，必须提交）
- `ui/` — 控制台前端工程（产物在 `ui/build/`，不提交）
- `dev/` — 对外接口与主题 API 文档，改行为要同步

## 3. 注释规范

只写「当前代码为什么是这样」。注释的价值在于解释约束、坑位与反直觉因果，不在于复述代码。

**不写**：日期与阶段标注（「（2026-11）」「阶段二」）；过程叙事（「原先…后来改成…」「修复了…」）；对语句的翻译（`// 设置状态` 跟在 `setStatus(...)` 上方）；同一信息在多处重复。

**必须写**：同步点（改了这里还要改哪里）；防错警告（「不能用 A，因为会 B」并用一句话说明后果）；平台契约（如爱发电要求立即返回 `{"ec":200,"em":""}`、签名规则）；阈值来源与换算。

**格式**：中文；单行注释够用就不写块注释；块注释超过 6 行必须有明确理由（结构契约、多步约定）；改代码时顺手修掉被改处的过时注释。

## 4. 提交规范

- 格式 `<type>: <中文描述>`，`type` 取 `feat` / `fix` / `refactor` / `perf` / `chore` / `docs`。
- 一次提交只做一件事；复杂改动在正文用 `-` 逐项列出「改了哪一面」。
- 只描述相对上一个提交的交付差异，不写开发过程（「发现问题 → 修复」属于日志，不属于提交信息）。
- 提交前用 `git status` 逐项核对：不得带入 `build/`、`ui/build/`、`ui/node_modules/`、`generated-images/`、`*.log`、`.idea/`；`gradle/wrapper/gradle-wrapper.jar` 与 `ui/pnpm-lock.yaml` 属于必须提交的文件。
- 版本号改动默认不包含在 AI 提交里，除非用户明确要求。

## 5. 架构契约

### 5.1 两套前端，别混

| 位置 | 归属 | 是否手写 | 是否提交 |
| --- | --- | --- | --- |
| `ui/src/**` | 控制台后台页面 | 是 | 是 |
| `ui/build/**` | 控制台产物 | 否 | 否 |
| `src/main/resources/static/dist/**` | 主题侧 `/reward` 页面脚本与样式 | 是 | 是 |

改主题侧脚本直接改 `static/dist`（无需构建，但要重新打包才进 JAR）；改后台页面改 `ui/src` 后必须 `pnpm --dir ui build`。两处不要互相引用。

### 5.2 配置的单一组装点

`vo/RewardConfigVo.from(basic, payment, records, afdian)` 是公开配置的唯一来源，`toMap()` 供 `GET /public/config` 使用；`RewardPageRouter` 与 `RewardFinderImpl` 都复用它。新增设置项要同步：`extensions/settings.yaml` 的表单项 → `config/` 的字段 → `RewardConfigVo.from()` → 需要暴露时加 `toMap()` → `dev/theme-api.md` 与 `README.md` 的表格。不要在端点里手拼 Map。

### 5.3 记录字段的同步点

新增/删除 `RewardRecord.Spec` 字段时，必须同时检查：模型 `extension/RewardRecord.java`、提交参数 `vo/SubmitParam.java`、公开接口组装 `endpoint/RewardPublicEndpoint.buildRecord()`、后台编辑 `service/RewardRecordService.updateSpec()` 与 `ui/src/components/RecordEditModal.vue`、CSV 导出 `endpoint/RewardConsoleEndpoint.export()`、以及 `README.md` 的字段表。历史上的 `tierLabel` 就是因为只加了模型、没有前台写入入口而被移除。

访客提交与后台补录共用 `support/InputSanitizer`：长度上限与图片地址白名单都放在那里，两侧必须同步；新增访客可控字段时同步那里与 `endpoint/RewardConsoleEndpoint.validateCommonFields()`。

超长字段的处理口径两侧不同，改动时别混：`InputSanitizer.clean()` 会**截断**，前台提交用它（访客超长静默截断，不报错）；后台写入走 `stripControlChars()` 只去控制字符，长度由 `validateCommonFields()` 按原始值判定并返回 400。若把清洗放在校验之前，超长会被先截断，校验就永远不会触发。

### 5.4 权限是两层的

后台能力由「插件接口权限」和「Halo 自动生成的扩展 CRUD 权限」两层构成，规则写在 `extensions/role-templates.yaml`：

- 查看角色（`plugin-reward-role-view`）只授予 `list`；导出 CSV 走的是带 name 的 `get`，因此归入管理角色。
- 管理角色（`plugin-reward-role-manage`）持有 `get` 与写操作。
- 新增后台接口时必须给对应角色补规则，否则超管之外一律 403。
- 前台控制台按钮的显示用 `utils.permission.has(["plugin:plugin-reward:records:manage"])` 判断，与后端规则保持同源。

Halo 的 RBAC 是精确匹配（`*` 之外没有前缀与通配），写规则前先按 `RequestInfoFactory` 与 `RbacRequestEvaluation` 的口径推演路径：

| 请求形态 | 解析结果 | 规则写法 |
| --- | --- | --- |
| `GET /records` | `resource=records`，无 name → `list` | `resources: ["records"]` + `verbs: ["list"]` |
| `GET /records/export` | `resource=records`，`name=export` → `get` | 同上资源，动词写 `get` |
| `PUT /records/{name}/status` | `resource=records`，子资源 `status` → `update` | 资源要写 `"records/status"`，只写 `records` 匹配不到 |
| `POST /records/{name}` | 有 name 的 POST 会被降级为**非资源请求** | 改用 PUT；非资源请求只认 `nonResourceURLs` |
| `POST /afdian/webhook` | 同上，且需要匿名访问 | 匿名角色用 `nonResourceURLs` 放行该路径 |

因此：**后台带 name 的动作接口一律用 PUT**，子资源路径要在 `resources` 里写全「资源/子资源」，匿名回调只能用 `nonResourceURLs`（动词写小写 HTTP 方法）。

### 5.5 响应式与异常

全链路 `Mono` / `Flux`，禁止在请求线程里阻塞（图片生成这类 CPU 工作用 `Schedulers.boundedElastic()`）。公开接口的异常分级：业务校验抛 `IllegalArgumentException` → 400 带 `{"message": ...}`；其余异常 → 500 并 `log.error`，不要把所有异常压成 400 把真实故障吞掉。内存限流统一用 `support/RateLimiter`（key 按「场景:来源 IP」区分），新增对外接口要给独立配额——爱发电回调曾因复用提交限流而丢单。

### 5.6 路由、模板与静态资源

- 页面路由固定 `GET /reward`，模板名 `reward`，主题提供同名模板时优先用主题的；模板 ID 由 `RewardPlugin.NAME` 拼接，不要写死字符串。
- 模板依赖主题的 `layout` 契约（`html (head, content)`）；页面变量由 `RewardPageRouter` 注入，Finder 面向主题自建页面。
- 前台资源只能通过 `/plugins/plugin-reward/assets/dist/...` 访问（`extensions/reverse-proxy.yaml` 只暴露 `static/dist`）；新增脚本要同时加进模板的 `<script>`。

## 6. 常见坑

1. **`.gitignore` 里不要出现未锚定的 `dist/`**：它会连 `src/main/resources/static/dist/` 一起忽略，导致发布包缺前台脚本，而本地因 `build/` 残留看不出问题。`/dist/` 才只忽略根目录。
2. **改完必须整包替换**：控制台产物文件名带哈希，改了 `ui/src` 就必须重新构建并替换整个插件 JAR，再强刷浏览器缓存（Cmd/Ctrl + Shift + R），否则新旧资源错配。
3. **`ui` 产物目录是 `ui/build/dist`，入包后是 `ui/`**：`processUiResources` 用 `Sync` 复制到 `resources/main/ui`；不要改回 `console/`。
4. **插件名三处一致**：`plugin.yaml` 的 `metadata.name`、`RewardPlugin.NAME`、`settings/configMap` 名称前缀。改名要全局替换，否则模板 ID、通知链接、反向代理路径全断。
5. **无单元测试是现状**：`test:unit` 为 `--passWithNoTests`，不要为凑覆盖率加空测试；验证靠 `clean build` 与真机冒烟。
6. **不要在代码里写死密钥**：爱发电 token 只从 Halo `Secret` 读取；日志中不得输出 token 原文。
7. **导出 CSV 的公式防护不要删**：访客可控字段以 `= + - @` 开头时会被表格软件当公式执行，`escape()` 里的前缀处理是安全边界。
