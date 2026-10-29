# AI Shell

面向运维人员的桌面 SSH 工作台：连接主机后在一个终端里操作，AI 助手在同一会话中理解上下文并执行命令，所有命令都进入审计日志。

前端是 Tauri 2 + React/TypeScript 窗口，后端是随应用启动的 Spring Boot sidecar；SSH、AI 代理、SQLite 与审计全部在后端完成，webview 不持有 SSH 凭据与访问令牌。

## 功能

- **连接管理**：一行为一条 Connection Profile（名称 + `用户@地址:端口`），同一主机可复用多条配置；私钥或密码加密保存，最新创建的排在最前，当前已连接的行右上角显示绿点。
- **终端**：xterm.js + JSch PTY，支持窗口大小同步、密码与主机指纹在终端内交互确认、审计就绪/受限徽标。
- **AI 助手**：基于 Google ADK 的流式对话，AI 命令与用户共享同一个 PTY，因此 `cd`、环境变量等状态连续；对话按主机持久化，跨重启保留，可“新对话”分段。
- **安全护栏**：危险命令 deny-list、每条命令超时、同一会话只允许一条命令在途、全局停止、完整审计（`audit.jsonl`，按大小轮转）。
- **只读文件浏览**：底部面板左右分栏——左侧从 `/` 开始的目录树（单击展开子目录），右侧六列表格（名称、大小、类型、修改时间、权限、用户/用户组），支持面包屑与上一级、文本/图片预览。不提供上传、下载、删除或在线编辑。

## 架构

```
┌────────────────────────────── WebView (React + xterm.js) ──────────────────────────────┐
│  连接 / 终端 / AI 对话 / 文件浏览        只通过一次性 ticket 访问 REST 与 WebSocket      │
└───────────────▲──────────────────────────────────────────────────────────▲────────────┘
                │ Tauri commands (backend_info / backend_ticket)             │ ticket
┌───────────────┴──────────────────────── Rust (Tauri 2) ───────────────────┴────────────┐
│  sidecar 监督、随机回环端口、bearer token、Windows 凭据管理器里的主密钥                   │
└───────────────┬────────────────────────────────────────────────────────────────────────┘
                │ 启动 java -jar backend.jar --server.port=<随机端口>
┌───────────────▼──────────────────── Spring Boot 4 sidecar ────────────────────────────┐
│  JSch：SSH 会话 / PTY / SFTP        ADK + Spring AI：对话与命令执行                    │
│  SQLite：主机、连接配置（密文）、对话、消息        audit.jsonl：命令审计                │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

关键约定：

- bearer token 只存在于 Rust 进程；webview 拿不到后端地址的访问凭据，只能换取 30 秒有效、单次使用、按用途（`terminal` / `hosts` / `files` / `chat`）绑定的 ticket。
- 密码与首次主机指纹确认由 JSch 的 `UserInfo` 渲染进终端，不使用系统弹窗，也不进日志。
- 连接配置密码/私钥口令使用 AES-256-GCM 加密，主密钥由 Rust 生成并存入 Windows 凭据管理器（`AI Shell/master-key`），通过环境变量传给后端；主密钥不可用时写密钥直接返回 503。
- 终端、AI 执行与文件浏览共用同一个 SSH Session（一次连接、一套凭据、一次指纹确认）。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 桌面壳 | Tauri 2（Rust，WebView2）、Windows Job Object 防孤儿进程 |
| 前端 | React 19、TypeScript、Vite 7、xterm.js 6 |
| 后端 | Java 21、Spring Boot 4.0.2（webmvc + websocket）、JSch 2.28.8 |
| AI | Google ADK 1.13.0 + `google-adk-spring-ai`、Spring AI 2.0.1，OpenAI 兼容网关（默认 `deepseek-v4.1-flash`） |
| 存储 | SQLite（`sqlite-jdbc`）、`audit.jsonl` 审计文件 |
| 打包 | Maven + jlink 运行时 + NSIS 安装包 / 便携 zip |

## 环境要求

- Windows 10/11 x64（当前只验证了 Windows 打包）
- JDK 21、Maven 3.9+
- Node.js 20+ 与 npm
- Rust stable 与 MSVC 构建工具（`cargo`、`link.exe`）
- WebView2 Runtime（Windows 11 自带）

## 本地开发

1. 安装前端依赖：

   ```bash
   npm install
   ```

2. 构建后端 jar（Rust 启动 sidecar 时优先读取 `backend/target/backend-0.0.1-SNAPSHOT.jar`）：

   ```bash
   cd backend
   mvn -o package          # 首次可去掉 -o 让 Maven 下载依赖
   cd ..
   ```

3. 启动桌面应用（Vite dev server + Tauri 窗口 + 后端子进程）：

   ```bash
   npm run tauri dev
   ```

   只调样式时可以 `npm run dev`，但页面依赖 Tauri 的 `backend_info` / `backend_ticket` 命令，脱离 Tauri 窗口无法连接后端。

4. 运行 AI 功能前设置模型密钥（只从环境变量读取，不会写入仓库或数据库）：

   ```powershell
   $env:DEEPSEEK_API_KEY = "sk-..."
   npm run tauri dev
   ```

## 测试

```bash
# 后端单元与集成测试（排除需要真实模型密钥的 live spike）
cd backend
mvn -o test "-Dtest=!DeepSeekSpikeTest"

# 前端类型检查 + 构建
npm run build

# Rust 侧
cd src-tauri
cargo test
```

需要真实主机的 live 用例默认跳过，设置以下环境变量后自动启用：

| 变量 | 说明 |
| --- | --- |
| `AISHELL_LIVE_HOST` / `AISHELL_LIVE_USER` / `AISHELL_LIVE_KEY` | 目标主机、用户、私钥路径 |
| `AISHELL_LIVE_PORT` | 可选，默认 22 |
| `AISHELL_LIVE_SHELL` | 可选，断言登录 shell（`bash` / `zsh`） |

## 打包

```powershell
# 便携包 + NSIS 安装包（跳过测试；先关闭正在运行的便携版，否则 jvm.dll 被占用）
powershell -ExecutionPolicy Bypass -File scripts/package.ps1 -SkipTests -Bundle nsis
```

产物：

- `src-tauri/target/release/bundle/nsis/AI Shell_0.1.0_x64-setup.exe`
- `release/AI-Shell-0.1.0-win-x64.zip`（解压后 `ai-shell.exe` 自带 `backend/backend.jar` 与 jlink 运行时，无需安装 Java）

不加 `-Bundle` 时只构建便携目录；不加 `-SkipTests` 时会先跑 `mvn package` 的测试。

## 配置

应用启动时由 Rust 注入，正常使用不需要手动设置：

| 变量 | 说明 |
| --- | --- |
| `AI_SHELL_TOKEN` | 后端 bearer token，Rust 生成随机会话密钥 |
| `AI_SHELL_MASTER_KEY` | 主密钥（base64），来自 Windows 凭据管理器 |
| `AI_SHELL_DATA_DIR` | 数据目录，默认 `%APPDATA%\com.aishell.desktop` |

可选变量：

| 变量 | 说明 |
| --- | --- |
| `DEEPSEEK_API_KEY` | 模型密钥（必填，AI 功能依赖） |
| `DEEPSEEK_BASE_URL` | OpenAI 兼容网关，默认 `https://sub.vcnovb.cn` |
| `DEEPSEEK_MODEL` | 模型名，默认 `deepseek-v4.1-flash` |
| `AI_SHELL_BACKEND_JAR` | 覆盖后端 jar 路径（开发调试用） |
| `AI_SHELL_JAVA` | 覆盖 java 可执行文件路径 |

数据目录内容：

- `ai-shell.db` — 主机、连接配置（密文）、对话与消息、ADK 会话
- `known_hosts` — SSH 主机指纹
- `audit.jsonl` / `audit.jsonl.1` … — 审计日志（默认 10 MiB 轮转、保留 5 份）
- 后端日志：`%LOCALAPPDATA%\com.aishell.desktop\logs\backend.log`

## 目录结构

```
src/                 React 前端（连接、终端、AI、文件面板、状态栏）
src/api/             调用后端 REST 的客户端（ticket 由 Rust 命令换取）
src-tauri/           Tauri 壳：sidecar 监督、ticket、凭据管理器、打包配置
backend/             Spring Boot sidecar（SSH/PTY/SFTP、ADK、SQLite、审计）
scripts/package.ps1  一条命令完成 jar → jlink → tauri build → 便携目录/zip
docs/adr/            架构决策记录（ADR 0001–0013）
GLOSSARY.md          领域术语表（Host / Profile / Session / Conversation …）
```

## 文档

设计取舍都记录在 `docs/adr/`，建议按顺序阅读：

| ADR | 主题 |
| --- | --- |
| 0001 | 本地 sidecar 架构 |
| 0002 | AI 全自动执行与护栏 |
| 0003 | 用户与 AI 共享终端、会话独占 |
| 0004 | Spring Boot 4 + ADK + Spring AI |
| 0005 | sidecar 握手与 token 归属 |
| 0006 | 终端 WebSocket 与 ticket |
| 0007 | 聊天 SSE 与 AI 命令执行 |
| 0008 | 连接配置与凭据存储 |
| 0009 | 复用终端 Session 的只读 SFTP 文件浏览 |
| 0010 | shell 钩子与用户命令审计 |
| 0011 | 对话持久化 |
| 0012 | 打包与分发 |
| 0013 | 原生窗口输入链路验证 |

## 已知限制

- 安装包未签名（SmartScreen 会提示），暂无自动更新与 MSI。
- 打包仅验证 Windows x64；jlink 运行时约 48 MB，fat jar 约 288 MB（ADK 传递依赖较大）。
- 一个 `地址:端口` 同时只维护一段“当前对话”，同一主机的并发 AI 轮次未串行化。
- shell 钩子只覆盖当前会话的 bash/zsh；嵌套或切换 shell 不会重新注入，历史抑制命令只能标记不能捕获原文。
- 文件浏览只读；不支持上传、下载、删除、在线编辑与右键菜单。
- AI 为全自动执行模式，靠 deny-list、超时与审计兜底，使用前请确认目标主机的信任边界。
