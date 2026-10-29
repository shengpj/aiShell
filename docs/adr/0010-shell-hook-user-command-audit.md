# Shell 钩子审计用户命令

用户敲进终端的命令也要进审计，但不能往目标机写文件（Q20=A）。做法：连接建立后，把一段 shell 钩子通过 stdin 注入当前交互 shell（`eval`），钩子在每次提示符出现时用 OSC 633 风格的标记（`ESC ] 633 ; AISHELL ; <kind> ; <payload> BEL`）上报上一条命令、退出码与 cwd。`TerminalBridge` 在转发 PTY 输出前用 `ShellMarkerFilter` 把标记从字节流里剥掉并分发，可见字节才发往 webview；`AuditLog` 作为 `UserCommandListener` 落一条 `kind=command, initiator=user`。**Why**：需求同时要求“用户命令可审计”和“不在目标机留文件”，只剩会话内注入一条路；标记走同一个 PTY 流，不需要第二条通道，也不会被用户的 `cat` 误读（扫描时就已剥离）；v1 只审计 bash/zsh，其余 shell 明确降级为“仅 AI 审计”，由 UI 徽标告知，而不是假装在审计。

协议四种标记：`I` 表示安装器停在 `read` 等注入，bridge 收到后把钩子脚本的 base64 作为一行输入送回；`H` 表示钩子已在首个提示符生效，payload 为 shell 名（`bash`/`zsh`）；`D` 表示一条命令结束，payload 为 `<exit>;<base64 cwd>;<base64 command>`；`W` 只在 bash 下出现，表示捕获能力变化，payload 是受控枚举 `history-off` / `ignorespace`，限制解除时发空 payload。base64 避免换行/控制字符破坏单行标记，cwd 用 `$PWD` 而不是额外调用 `pwd`；`W` 的取值集合固定，直接明文更便于排查。**Why**：标记必须单行且能在任意字节流里无歧义识别；payload 用 base64 后长度可预估，`ShellHook.MAX_PAYLOAD_CHARS = 3800` 卡在 PTY 规范模式 4096 字节行上限以内（实际 bash 载荷约 1676 字符）。

**审计轮转**：`AuditLog` 每次追加前检查 `audit.jsonl` 的大小，达到 `ai-shell.audit.max-bytes`（默认 10 MiB）就把当前文件改名为 `audit.jsonl.1`，旧档案依次后移，最多保留 `ai-shell.audit.max-files`（默认 5）份，最旧的被删除。**Why**：桌面端长期运行不能让审计文件无限增长，而审计又必须始终可追加；单条记录可能大于阈值，所以先写满再轮转，当前记录不会因为轮转而丢失。验证：`AuditLogTest` 用 300 字节阈值、2 份档案验证轮转、最新记录落在当前文件、档案数量受上限约束。

历史设置会让命令进不了 `history`（`set +o history`、`HISTCONTROL=ignorespace`），钩子因此看不到它们。bash 钩子在每个提示符前复查 `shopt -qo history` 与 `HISTCONTROL`，状态变化时发 `W`；bridge 把徽标切到“审计受限 · bash”，tooltip 说明原因，恢复后切回“审计就绪”。**Why**：静默漏记比显式降级更危险，用户至少要能看到哪些命令不会被记录。zsh 用 `preexec_functions` 拿命令文本，不经过历史，不受这两个设置影响。

安装器是一条普通命令行：

```sh
printf '\033]633;AISHELL;I;\007' >&2; read -rs __p; eval "$(printf %s "$__p" | base64 -d)"; unset __p; printf '\033[1A\033[2K\r'
```

`I` 走 stderr 且不带换行，所以它先到；`read -s` 不回显；执行完把终端上已回显的安装行（以及 shell 在命令结束后补的回车换行）用 `\033[1A\033[2K\r` 抹掉，用户只看到原提示符重新出现。注入期间 `TerminalBridge` 缓冲 webview 的输入（上限 4096 字节），`H` 到达后按原顺序释放；`readinessLocked()` 在安装中返回“the terminal is still installing command auditing; try again in a moment”，所以 AI 命令不会插进注入过程。4 秒仍未 `H` 则 watchdog 发 Ctrl-C 并降级。**Why**：注入本质是往用户 shell 里打一行命令，缓冲与就绪门保证用户输入不会混进 `read`，也不会和 `eval` 抢 stdin；安装失败时宁可打断也不能把 shell 留在 `read` 里。

bash 钩子挂在 `PROMPT_COMMAND` 前部（字符串与数组两种形态都兼容，按 `declare -p` 的形态分别拼接）；zsh 用 `preexec_functions` / `precmd_functions`。bash 的 `PROMPT_COMMAND` 在每条命令后、下个提示符前执行一次，正好拿得到 `$?`；但它在首屏也会执行一次、且会在空回车后重复，所以钩子按 `history 1` 解析出的历史编号去重：编号没变就跳过，第一次执行只发 `H` 并记下编号。该编号同时用于自删除：安装行本身会被 bash 记进历史，钩子解析出编号后 `builtin history -d` 掉，`history` 里不留痕迹。**Why**：bash 4.2 的 `HISTCMD` 比实际编号大一（实测），按 `history 1` 的编号删除才是可靠的；不用 `HISTIGNORE`/`HISTCONTROL` 改动用户配置，钩子不写 rc 文件，也不在磁盘上留脚本。

AI 经 `runAiCommand` 执行的包装行（`echo <<AI_SHELL:token:BEGIN>>; cmd; echo <<AI_SHELL:token:END>>`）会在 history 里产生多条记录，其间的提示符会让钩子报出 `D`。抑制规则：`aiRunning` 为真时收到 `D` 一律丢弃（包装行内的 `D` 必然早于 END 捕获标记）；AI 结束后保留一个“宽限位”（`aiSuppressUserCommand` + 5 秒 deadline，AI 输出与 END 捕获会刷新它），之后第一条 `D` 视为 END 行的回显再丢弃一次，再往后即用户命令。超时或 AI 命令超时都会清掉该位，避免吞掉真实用户命令。**Why**：钩子不能自己按命令文本过滤 BEGIN/END——包装行的历史文本被 shell 重写后并不可靠，而且过滤掉 BEGIN/END 后宽限位会误吞用户的下一条命令；按“AI 是否在跑”在 bridge 侧判定是确定性的。

shell 识别用一条独立的 exec 通道 `printf 'AISHELL-SHELL=%s\n' "$0"`，取 basename、去掉登录 shell 的前导 `-`、小写后只认 `bash`/`zsh`；其他结果（`fish`、`csh`、空、探针失败）一律发 `{"type":"hook","state":"unavailable"}` 并附原因，终端照常可用，只是用户命令不入审计。**Why**：在登录 shell 里再 fork 一个 `$SHELL -c` 可能得到与当前 PTY 不同的 shell；`$0` 就是当前 shell 本身。exec 通道与 SFTP 一样复用同一条 Session，不额外认证。

前端 `TerminalPane` 在连接栏最左侧渲染审计徽标：`installing` 显示“审计安装中”、`ready` 显示“审计就绪 · bash”（带说明 tooltip）、`unavailable` 显示“审计降级”（tooltip 给出原因，如 “the shell did not answer the audit hook”）。连接、断开、出错、关闭时清空徽标。**Why**：审计边界必须对用户可见；降级不是静默失败，用户能立刻知道“这台机器上的手工命令不会被记录”。

**验证状态**：后端 92 个测试全绿（2 个 live spike 跳过），新增 19 例：`ShellMarkerFilterTest` 6（剥离、跨 chunk 重组、ST 终止符、半截前缀透传、D 载荷、超长 pending 冲刷）、`ShellHookTest` 5（shell 名归一化、载荷解码含 PROMPT_COMMAND/history/H/D 或 precmd/preexec、长度上限、安装器含 `read -rs`/`base64 -d`/擦除序列且不含 `history`）、`TerminalBridgeHookTest` 6（注入与缓冲输入释放、标记不外泄、D 回调解码、AI 包装行抑制且后续用户命令只记一次、fish 降级不写 PTY、watchdog 4 秒降级并写 Ctrl-C）、`AuditLogTest` 2（用户条目字段、AI 条目字段）。真实主机 `121.41.177.215` 上验证：连接后约 750 ms 收到 `installing` → `ready (bash)`；webview 收到的二进制帧中没有 OSC 633 标记；安装行在远端历史中不存在（`history` 相邻条目连续）；`cd /tmp`、`false`、`history | tail -5` 等命令在 `audit.jsonl` 里带正确 cwd 与退出码（`false` → 1）；原始字节确认安装行被 `\033[1A\033[2K\r` 擦除。随后在 Chrome + `__TAURI_INTERNALS__` 桩下走 UI：徽标序列为“审计就绪 · bash” → “(none)” → “审计安装中” → “审计就绪 · bash”，WS 收到 `installing`/`ready` 两帧，界面上敲 `pwd` 后审计新增 `{"kind":"command","initiator":"user","command":"pwd","exitCode":0,"cwd":"/root"}`。原生窗口与真实键盘输入链路后来在打包版上补验：徽标进入“审计就绪 · bash”后，`SendInput` 真实键入 `echo NATIVE-KEY-OK`，终端回显正确且 `audit.jsonl` 记录 `initiator:"user"`、退出码 0、cwd `/root`（ADR 0013）；此前桩页验证因隐藏标签页接收不了 CDP 键盘事件用的是合成 keydown。

zsh 分支随后在真实主机验证（2026-10-28）：测试机 CentOS 7 上安装 `zsh 5.0.2` 并把 root 登录 shell 临时切到 zsh，验证后 `usermod -s /bin/bash root` + `yum remove zsh` 还原。新增 gated live 测试 `ShellHookLiveTest`（`AISHELL_LIVE_HOST` / `AISHELL_LIVE_USER` / `AISHELL_LIVE_KEY` / 可选 `AISHELL_LIVE_SHELL`），经真实 JSch 通道断言：`opened` 后钩子 `ready` 的 shell 为 `zsh`、`echo LIVE-HOOK-OK` 审计为 `0|/root|…`、`false` 为 `1|/root|false`、`cd /tmp` 后的 `pwd` 的 cwd 为 `/tmp`；同一测试在 bash 下再跑一遍，并覆盖 `HISTCONTROL=ignorespace` → 徽标 `degraded` → 清空后回到 `ready`。AI 包装行抑制也在原生窗口 live 运行中确认（ADR 0013）。整套后端测试 107 例全绿（3 个需要外部环境的 live 用例默认跳过）。

**已知取舍**：`HISTCONTROL=ignorespace` 下的前导空格命令与 `set +o history` 之后的命令仍无法捕获文本（钩子只能看到历史），现在会发 `W` 让徽标显示“审计受限”，不再静默漏记；要真正捕获需在用户 shell 里挂 DEBUG trap，会顶掉用户自己的 DEBUG trap、且在管道/复合命令里只能拿到第一条简单命令，暂不采用；会话内再切 shell（`bash`、`su`）不会被重新注入，子 shell 的命令不进审计——曾尝试导出 `PROMPT_COMMAND` 与钩子函数让子 bash 继承，但 CentOS 的 `/etc/bashrc` 默认设置 `PROMPT_COMMAND`，导出会把父 shell 的提示符命令一并带进子 shell，且 zsh 与 `su -` 仍不生效，故撤回；fish/csh 等只降级，不审计；PTY 单行 4096 字节限制决定了载荷上限，超长命令会被截断而不是拆行上报；审计落 `audit.jsonl`，超过 `ai-shell.audit.max-bytes`（默认 10 MiB）时轮转为 `audit.jsonl.1`、旧档案依次后移，最多保留 `ai-shell.audit.max-files`（默认 5）份，最旧的被删除。
