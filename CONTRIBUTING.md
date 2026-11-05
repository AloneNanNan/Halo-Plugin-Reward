# 贡献指南

感谢你愿意为 **打赏插件（plugin-reward）** 贡献代码！本仓库是 Halo 插件，构建分后端（Gradle + Java）与控制台前端（Vue + Vite）两部分，和普通前端项目略有不同。

**两份文档的分工**：本文件写**协作流程**（环境准备、自检、提 PR）；代码库知识——目录约定、构建产物、权限模型、注释规范、常见坑——统一维护在根目录 [`AGENTS.md`](./AGENTS.md)，**改代码前请先读对应章节**。

> 面向 AI 协作者：请先读 AGENTS.md 的「硬规则」；AI 不得私自 `commit` / `push`，也不得改动版本号。

## 快速开始

需要 **Halo >= 2.27.0**、**JDK 21**、**Node.js 22+** 与 **pnpm 9**；用 DevTools 起本地 Halo 还需要 **Docker**。

```bash
# 克隆项目（Fork 后请 clone 你自己的仓库）
git clone https://github.com/AloneNanNan/Halo-Plugin-Reward.git
cd Halo-Plugin-Reward

# 安装控制台前端依赖
pnpm --dir ui install
```

常用命令：

| 命令 | 作用 |
| --- | --- |
| `./gradlew haloServer` | 起本地 Halo（Docker），默认 <http://localhost:8090/console>，账号 `admin` / `admin` |
| `./gradlew reload` | 把当前代码热加载进本地 Halo |
| `./gradlew clean build` | 完整构建：控制台 UI → 插件 JAR，产物在 `build/libs/` |
| `./gradlew compileJava` | 只编译后端，改 Java 时快速验证 |
| `pnpm --dir ui build` | 只构建控制台 UI（含 `vue-tsc` 类型检查） |
| `pnpm --dir ui dev` | 控制台 UI 的 watch 模式 |
| `pnpm --dir ui lint` | 控制台 UI 的 oxlint + eslint 检查（带 `--fix`） |

> 产物说明：`build/`、`ui/build/` 都是构建产物，勿手改也勿提交。注意 `src/main/resources/static/dist/` 是主题侧 `/reward` 页面用的**手写**脚本与样式（不是构建产物），需要随代码提交。

## 本地调试

1. `./gradlew haloServer` 启动后，在后台「插件」里启用打赏插件，进入设置完成收款码等配置。
2. 打开 <http://localhost:8090/reward> 查看看前台页面；提交一条记录后到「内容 → 打赏」确认，确认后应出现在赞助者墙。
3. 主题侧片段可在任意主题模板中用 `th:replace="~{plugin:plugin-reward:reward-modal :: button}"` 引用。
4. 爱发电相关功能需要开发者 `user_id` 与 API Token：Token 存进 Halo 的「密钥」后在插件设置里选择，**不要写进代码或配置文件**。回调地址为 `https://你的域名/apis/api.reward.nanzhiy.cn/v1alpha1/afdian/webhook`，本地调试需要公网可达地址。

## 提交前自检

仓库的 CI（`.github/workflows/ci.yaml`）会在 push 与 PR 时执行构建，但建议先在本地跑通：

```bash
./gradlew clean build     # 后端编译 + 控制台 UI 构建 + 打包，必须通过
git status --short        # 确认没有 build/、ui/build/、node_modules、generated-images 混入
```

改到下列内容时，请额外做一次冒烟验证：

| 改动范围 | 冒烟项 |
| --- | --- |
| 前台模板或 `static/dist` | `/reward` 页面样式、二维码放大、弹窗提交、赞助者墙加载更多 |
| 后台记录页 | 列表、筛选、确认 / 拒绝、批量操作、CSV 导出（用表格软件打开确认无公式执行） |
| 权限模板 | 只给「查看」权限的角色应看不到新增 / 导出 / 批量 / 确认 / 删除 |
| 爱发电 | 「同步爱发电」能拉到订单，昵称头像补全；回调能入库 |
| 设置表单 | 新增或改名的字段能在后台保存并读到 |

## 提 PR 流程

常规流程：**Fork → 建分支 → 改代码 → 本地自检 → 提交 → 提 PR**。

### 1. 建分支

```bash
git checkout -b feat/xxx        # 新功能
git checkout -b fix/xxx         # 修复
git checkout -b refactor/xxx    # 重构
git checkout -b docs/xxx        # 文档
```

### 2. 提交信息规范

格式：`<type>: <中文描述>`，关联 issue 时在末尾加 `(#N)`，多个用空格分隔。

- `feat` — 新功能；`fix` — 修复；`refactor` — 重构；`perf` — 性能；`chore` — 构建、依赖等杂项；`docs` — 文档
- 一次提交只做一件事；复杂改动在正文用 `-` 逐项列出
- 只写该提交的交付差异，不写「发现问题 → 修复」的开发过程

示例：

```
feat: 支持按状态筛选打赏记录
fix: 修复弹窗提交成功后按钮无法再次点击
```

> 完整规则（含 AI 侧约束）见 AGENTS.md 的「提交规范」。

### 3. PR 描述模板

```markdown
## 改动内容

（这次改了什么，一句话或列表说明）

## 为什么需要

（修复的问题 / 需求背景）

## 验证方式

（本地跑过哪些命令，在哪个 Halo 版本上验证过，如何复现验证）

## 是否涉及设置项 / 权限 / 数据模型

（改动了 settings.yaml、role-templates.yaml 或 RewardRecord 字段？是的话说明对现有站点的影响）
```

## 其他

- **硬性规定**：不得修改版本号（`src/main/resources/plugin.yaml` 的 `spec.version` 与 `gradle.properties` 的 `version`，由维护者按发布节奏统一提升）；不得提交构建产物；新增运行时依赖需在 PR 中说明理由。
- **数据与安全**：涉及访客隐私字段（联系方式、交易单号）、导出、权限、外部请求的改动，请在 PR 描述中写明影响面；不要在日志里输出 Token。
- **报 Bug / 提建议**：请在 [Issues](https://github.com/AloneNanNan/Halo-Plugin-Reward/issues) 中说明 Halo 版本、插件版本、复现步骤与预期结果。
- **使用疑问**：也可先查阅 [README](./README.md) 与 [`dev/`](./dev) 下的接口文档。
- **许可证**：本项目基于 [GPL-3.0](./LICENSE) 开源，提交贡献即代表同意代码以 GPL-3.0 协议分发。项目最初由 Halo 官方脚手架 [`halo-dev/plugin-starter`](https://github.com/halo-dev/plugin-starter) 生成。
