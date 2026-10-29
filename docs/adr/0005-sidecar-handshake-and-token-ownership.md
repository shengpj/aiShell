# sidecar 握手：随机端口 + Rust 持有令牌

Tauri 启动 sidecar 时自行挑选一个空闲回环端口（`TcpListener` 绑 0），生成 UUIDv4 令牌并通过 `AI_SHELL_TOKEN` 环境变量注入 Java 进程，后端只监听 `127.0.0.1`；Rust 以带 Bearer 的 `/api/health` 轮询作为就绪握手，前端只通过 `backend_info` / `backend_health` 命令拿状态与模型名，从不接触令牌或 base URL。**Why**：固定端口会撞上用户机器上别的服务，令牌若进入 webview 就可能随前端日志、崩溃报告或 XSS 泄漏；把它留在 Rust 一侧，webview 即便被注入脚本也无法直连后端。**后果**：任何新的数据通道（S2 的 PTY WebSocket、聊天流）都得先决定是"前端带令牌直连"还是"Rust 代理"，在做出前一条会破坏令牌不出 Rust 的前提；sidecar 崩溃或退出的检测目前只有轮询与 `RunEvent::Exit` 时的 kill 兜底，进程被强杀时可能残留 Java 进程（日志在 `app_log_dir/backend.log` 可诊断）。

S1 已按此实现并验证：`Sidecar::spawn` 用随机端口与令牌拉起 `java -jar`（Windows 加 `CREATE_NO_WINDOW`），`wait_ready` 在 90 秒内拿到 `UP` 与模型名，无令牌请求返回 401；窗口关闭后 `RunEvent::Exit` 触发 kill，Java 进程与端口都不残留。前端在桌面端状态栏显示"后端已连接: 127.0.0.1:<port>"与当前模型。
