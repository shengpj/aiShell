# 原生窗口交互验证

此前所有 UI 路径都在 Chrome + `__TAURI_INTERNALS__` 桩下验证：`ChatPane`/`TerminalPane` 里真实控件能被 Playwright 驱动，但应用内的浏览器面是隐藏的（0×0 视口），CDP 的指针事件和 `press_key` 到不了 xterm 的隐藏 textarea，只能用合成 `keydown` 绕过。原生 Tauri 窗口（WebView2 + 真实键盘鼠标）的链路一直是空白。本轮（2026-10-28）用 Windows UI Automation 定位控件、`SendInput`/`mouse_event` 注入真实输入，在**打包后的应用**上把整条链路走了一遍。

**Why**：合成事件能证明 React 逻辑，但不能证明“用户真的点得到、打得进”。WebView2 的焦点、命中测试、中文/Unicode 输入、xterm 的 textarea 焦点转发只有真实窗口能覆盖。

方法：PowerShell 加载 `UIAutomationClient`，每次操作前从 `AutomationElement.FromHandle(MainWindowHandle)` 重新枚举 `Edit`/`Button` 子树并读取 `BoundingRectangle`（UI 会因连接状态变化重新布局，缓存坐标会点偏）；点击用 `SetCursorPos` + `mouse_event` 按下/抬起；文本用 `SendInput` 的 `KEYEVENTF_UNICODE` 逐字符发送（不经过输入法，中文也能进），回车用 `VK_RETURN`；截图用 `GetWindowRect` + `CopyFromScreen`。UIA 的 `Terminal input`（xterm 的 textarea）矩形为 `Empty`，终端区域按坐标点击，键盘由 xterm 自己转发。

**验证结果**（打包版 `release/AI-Shell-0.1.0-win-x64`，真实主机 `121.41.177.215`、`C:\Users\Administrator\fanqie.pem`）：

- 连接表单：鼠标点击三个输入框、`Ctrl+A` 全选、键盘输入 `121.41.177.215` / `root` / 私钥路径，点击“连接”；终端出现 host-key 指纹提示，点击终端后键盘输入 `yes` + 回车，连接建立，徽标显示“审计就绪 · bash”，右侧显示“终端已连接”，文件树自动列出 `/root`。
- 终端键盘：点击终端后输入 `echo NATIVE-KEY-OK` + 回车，终端回显 `NATIVE-KEY-OK`；`audit.jsonl` 出现 `{"kind":"command","initiator":"user","command":"echo NATIVE-KEY-OK","exitCode":0,"cwd":"/root"}`。
- 文件树点击：点击 `.cache` 后展开显示 `pip` 子目录，列表继续列出 `.ssh`、`.bashrc` 等条目。
- AI 聊天：点击右侧输入框、键盘输入 `run echo NATIVE-AI-OK and report the output`、点击“发送”；模型回复、命令卡片（`退出码 0 · 34 ms`）、最终文本全部渲染；终端里可见 AI 脚本执行，`audit.jsonl` 追加 `initiator:"ai"` 的 `echo NATIVE-AI-OK` 记录；`chat_message` 4 行、`agent_event` 8 条、`agent_session` 键为 `121.41.177.215:22`。
- 关闭与重启：`CloseMainWindow` 后应用与 java 子进程正常退出；重新启动（有效 `DEEPSEEK_API_KEY` 仅注入本次进程环境）并再次连接，指纹提示不再出现（known_hosts 已持久化），AI 面板恢复 4 条历史；追问 `which command did you run earlier…`，模型回答 `You ran: echo NATIVE-AI-OK`，说明 ADK 事件跨进程重启被正确加载为模型上下文（补上了 ADR 0011 的未验证项）。
- 强杀场景另行验证：`Stop-Process -Force` 父进程后 Job Object 一并回收 java，无残留。

**已知取舍**：本机 100% DPI、单显示器、单会话，未覆盖多屏/缩放/远程桌面下的坐标换算；UIA 矩形在窗口移动后立即过期，脚本必须每次重新查询，不能用固定坐标；xterm 的 textarea 没有 UIA 矩形，终端只能按区域点击；脚本只做人工触发的验收，不进 CI（需要交互式桌面与会话）。AI 回复速度依赖中转网关，本轮首 token 约 1 s、整轮约 10 s，与 S3 记录的 50 s 不同，属正常波动。
