# AI Shell

面向运维人员的桌面工具：通过 SSH 连接主机，在终端中操作，并由 AI 代理在同一会话中理解上下文、执行命令。

## Language

**Host（主机）**：
一台可通过 SSH 到达的目标机器，由地址和端口标识。
_Avoid_: 服务器、Server、节点、Node

**Connection Profile（连接配置）**：
一组用于连接某台 Host 的命名凭据与连接参数；同一 Host 可以有多套 Connection Profile。
_Avoid_: 连接、Connection、账号、Account

**Session（会话）**：
一次指向单台 Host 的活动 SSH 连接，终端与文件浏览共用同一 Session。
_Avoid_: 连接、Connection、Channel

**Action Proposal（动作提案）**：
AI 在对话中表达的、准备在某个 Session 上执行的一条命令或一组有序命令，附有其给出的理由；全自动模式下它不构成确认门。
_Avoid_: 建议、Suggestion、推荐、Recommendation

**Command Execution（命令执行）**：
一次真正在 Session 上执行过的命令，包含输出、退出状态与发起者（用户或 AI）。
_Avoid_: 任务、Task、Job

**Terminal（终端）**：
Session 的交互式 PTY 视图；用户与 AI 共享同一个 shell 状态。
_Avoid_: 控制台、Console、命令行窗口

**Session Lock（会话独占权）**：
AI 在某个 Session 上执行命令期间独占终端写入的权利；持锁时用户输入默认暂停，遇到需要人工输入的提示时临时交还，用户也可主动接管或按 Esc 中止。
_Avoid_: 锁、Mutex、互斥锁

**Conversation（对话）**：
围绕一台 Host 持续存在的 AI 对话，跨 Session 存活，并持久化到本地 SQLite；以 Host 的地址与端口标识，Session 只是它观察与操作该 Host 的通道。一个 Host 可以有多段对话：点“新对话”后序号 +1，新对话的标识为 `地址:端口#N`（第一段仍是 `地址:端口`），旧对话留在库中但不再参与上下文。
_Avoid_: 聊天、Chat、Thread、会话（已被 Session 占用）

**Shell Hook（shell 钩子）**：
注入到目标机交互 shell 会话内的钩子，在每次显示提示符时上报上一条用户命令、退出码与 cwd；只存在于当前会话，不写入目标机磁盘。
_Avoid_: shell 集成脚本、rc 修改

**Audit Log（审计日志）**：
按时间追加的记录流，包含每条 Command Execution 的发起者（用户或 AI）、Session、命令、退出码与 cwd（用户命令）或输出与状态（AI 命令）；用户命令依赖 Shell Hook，钩子降级时只有 AI 命令入账。落盘为 `audit.jsonl`，按大小轮转并保留有限份数的历史文件。
_Avoid_: 日志、Log、历史记录
