# 用户自配置的模型档案与按轮选择

模型连接信息原来写死在 `application.yml`（`spring.ai.openai.*` + `adk.spring-ai.*`），密钥来自 `DEEPSEEK_API_KEY`，换网关或换模型都要改配置、重启、重新打包。本轮（2026-10-30）把 base URL、模型名、API Key 做成用户数据：`model_profile` 表 + `/api/models` + 聊天面板里的“模型设置”，一轮对话可以指定用哪个模型。采样温度不开放给用户，固定 0.2，表单里也不再出现该字段。

**Why**：应用是发给别人用的，使用者不一定用 DeepSeek，也往往有多个网关/模型（快模型跑日常、强模型排查疑难）。把密钥留在 yml 里既要求每个使用者重新构建，也让“密钥不落仓库”只能靠自觉。做成用户数据后，第一次启动在界面里填一次即可，密钥与主机凭据走同一套加密路径。

## 设计

### 存储

```sql
CREATE TABLE IF NOT EXISTS model_profile (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, base_url TEXT NOT NULL, model TEXT NOT NULL,
  api_key TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
```

- `api_key` 存 `SecretCipher`（AES-256-GCM，`v1:` 前缀）的密文，主密钥仍由 Rust 放进 `AI_SHELL_MASTER_KEY`；没有主密钥时接口返回 503，和主机凭据一致。
- 列表只返回 `hasApiKey`，密钥永不回到 webview；`PUT` 省略/留空 `apiKey` 表示保留原值。
- 删除是硬删除：档案只是配置，没有审计含义（与主机的软删除不同）。
- 早先版本的 `model_profile` 带一列 `temperature`；改成固定值后 `initSchema` 检测到该列就 `ALTER TABLE … DROP COLUMN`（SQLite 3.35+），否则残留的 `NOT NULL` 列会让不写该列的 `INSERT` 失败。

### 选择与缓存

- `POST /api/chat` 增加可选 `modelId`；缺省用最早创建的档案（`ORDER BY created_at, id LIMIT 1`），没有档案时 409 并提示去设置里添加。
- `ModelService.forTurn(modelId)` 每次对话开始前解析档案，按 `updated_at` 缓存 `SpringAI` 实例，更新/删除时失效，避免每轮重建 HTTP 客户端。
- 聊天面板输入框下放模型下拉（显示档案名，右侧显示真实模型名），只影响之后发送的轮次；历史轮次不记录用的是哪个模型。

### 启动与自配置

- `spring.ai.model.*` 全部设为 `none`（chat/embedding/image/moderation/audio），关掉 OpenAI 自动配置，否则它会在启动时就要求 API Key。
- `ChatModelFactory` 用 `OpenAiSetup.setupSyncClient` + `setupAsyncClient` 按档案建客户端，再交给 `OpenAiChatModel`；温度固定 0.2（`TEMPERATURE` 常量）。只传同步客户端不够：`OpenAiChatModel.Builder.build()` 会为流式再懒建一个异步客户端，用的是 builder 自己（未设置）的凭据，启动或首轮就会抛 “At least one credential source must be specified”。
- `/api/health` 的 `model` 改为默认档案名，状态栏在没有档案时不显示模型段。

### 票证

`/api/models` 复用 ticket 机制，新增 purpose `models`；Rust 只转发 purpose，不用改。

## 验证

- `ModelServiceTest`：密钥密文落库、脱敏返回、字段校验、更新保留/替换密钥、默认档案、缓存命中与更新失效、删除、空目录 409、未命名档案 404、无主密钥 503、密文损坏 409。
- `ModelEndpointTest`：无凭据 401、`models` ticket 一次性且别的 purpose 无效、创建/列表不泄露密钥、留空密钥保留旧值、删除与 404、非法 base URL 400。
- `ChatEndpointTest`：带 `modelId` 正常流式返回，未知 `modelId` 404；聊天测试用 `@Primary ChatModels` 假实现替换真实档案。
- `DeepSeekSpikeTest`（需 `DEEPSEEK_API_KEY`）仍走真实网关，验证 `ChatModelFactory` 的流式与工具调用。
- 打包前用测试数据目录起后端做过一次接口冒烟：创建档案 → 列表 → `ai-shell.db` 中只有 `v1:` 密文、无明文密钥，`/api/health` 返回档案名。
- 全量 `mvn -o test` 150 例通过（1 例 live 跳过），前端 `tsc --noEmit` 与 `vite build` 通过。
- 打包版在原生窗口里用 UIA 走完增删链路：点“模型设置” → 空状态“还没有模型，添加后即可在聊天中选择” → “+ 添加模型”表单四个字段（名称 / Base URL / 模型 / API Key，值全为空、只有灰色提示语，没有温度）→ 提交按钮在新建时是“添加”、编辑时是“保存” → 列表出现 `验证用2 · test-model · https://api.example.com`，聊天区下拉从“选择模型”变成可选、旁边显示真实模型名，状态栏显示当前默认档案；点“删除”弹出系统对话框“删除模型 验证用2？已保存的 API Key 也会一并删除。”，确认后该行消失。这一轮用户的真实档案（`deepseek / deepseek-v4.1-flash / https://sub.vcnovb.cn/v1`）经历了 `DROP COLUMN temperature` 迁移，迁移后字段与 `v1:` 密文 key 都还在。真实模型轮次没有在打包版里跑（需要连主机），由 `DeepSeekSpikeTest` 与接口冒烟覆盖。

**取舍**：档案全局共享，不与主机绑定——同一台机器连不同主机时通常仍想用同一个模型；如果将来需要“按主机固定模型”，给 `host` 加一列即可。密钥仍由本机主密钥加密，导出数据库到别的机器不可直接使用。模型下拉只影响新一轮，不提供“重跑上一轮”的模型切换。
