# 聊天走 SSE，AI 命令直接写进共享 PTY

聊天通道沿用 ADR 0006 的 ticket 模式：webview 用 `fetch` POST `/api/chat`，ticket 放在 `X-AI-Shell-Ticket` 请求头，响应体是 SSE 流；长期 bearer 令牌仍只留在 Rust。ADK 侧每次回合构造一个只挂 `runCommand` 工具的 `LlmAgent`，工具实现经 `TerminalSessions` 找到会话，调用 `TerminalBridge.runAiCommand`（ADR 0003）把命令写进用户正在看的那个 PTY，用随机标记行截取输出与退出码。**Why**：聊天是单向文本流，EventSource 不支持自定义请求头也不支持 POST 请求体，所以只能 `fetch` + ReadableStream 手工解析 SSE；工具直接复用共享 PTY 而不是另开 SSH 会话，才能让用户全程看到 AI 在做什么，并让密码/确认提示转交给同一个终端输入。

事件约定：`text`（增量文本）、`command`（`started` 带命令与超时、`finished` 带状态/退出码/耗时）、`error`、`done`。同一会话同时只允许一个回合（`active` 集合占位），`POST /api/agent/stop` 对所有会话执行 `cancelAiCommand`，取消后卡片显示 `cancelled` 并写入审计。命令仍受 ADR 0002 的硬拦截（`AiCommandGuard` 黑名单）与每条命令超时（`ai-shell.ai.command-timeout-seconds`，默认 120s）约束；时序预算另加标记超时 15s（15s 内看不到 BEGIN 标记即判定非 POSIX shell）与静默等待（输出安静 400ms 才下笔，最多等 3s）。

交互提示接管：输出里出现 password/passphrase/yes-no 类提示时，桥接层把 AI 状态切成 `takeover`（前端徽标显示“等待输入”），此后用户的敲键直接写进 PTY，回车结束接管并恢复计时（接管时间不计入超时）；Esc 取消 AI 命令；`{"type":"takeover","enabled":bool}` 支持用户手动接管/交还。AI 命令运行期间非接管的敲键会被丢弃，避免混入脚本。

**ADK 适配器补丁**：`google-adk-spring-ai` 转换流式块时用“文本是否以 `.`/`!`/`?`/换行结尾”推断 `partial`，这是给 Gemini 的启发式；模型的最后一个流式块若以其他字符结尾（常见于中文或代码块），ADK 会认为回合未结束并再次调用模型，直到 500 次上限。`TurnFinishingModel` 用 `buffer(2,1)` 延迟一块，只在流的最后一块清除 `partial`，工具调用块与中间块保持原样。该补丁只针对适配器行为，升级 ADK/Spring AI 后应重新验证并优先删除。

**S3 验证状态**：后端单测覆盖 `TerminalBridgeAiTest`（标记截取、Esc 取消、密码提示接管、手动接管、运行期间丢弃敲键、未完成命令行拒绝、全屏程序拒绝、超时中断）、`ChatEndpointTest`（假模型经 SSE 触发工具执行、黑名单拒绝、未连接会话 409）、`TurnFinishingModelTest`（4 例）。真机路径：以 `C:\Users\Administrator\fanqie.pem` 密钥登录 `121.41.177.215` 后，聊天“执行 echo S3-OK”依次产出文本增量、命令卡片（退出码 0 · 22ms）与 `audit.jsonl` 记录；`停止` 让 `sleep 120`/`sleep 90` 分别以 `cancelled (stopped by user)` 结束并写审计；手动接管后输入的字符在终端回显，回车自动交还 AI。该中转模型首轮首个 token 约 50s（`reasoning_content` 先于正文），超时预算按此留足。原生窗口内的链路随后在打包版上补验（ADR 0013）：点击聊天输入框、真实键入指令、点击“发送”后命令卡片（退出码 0）与最终文本正常渲染，审计同时记录 `initiator:"ai"` 条目；此前的 UI 验证走真实 Chrome + 仅测试用的 `__TAURI_INTERNALS__.invoke` 桩。
