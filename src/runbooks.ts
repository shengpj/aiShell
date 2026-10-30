export interface RunbookField {
  id: string;
  label: string;
  placeholder: string;
}

export interface Runbook {
  id: string;
  title: string;
  summary: string;
  category: string;
  fields: RunbookField[];
  steps: string[];
  success: string[];
}

export const RUNBOOKS: Runbook[] = [
  {
    id: "host-health",
    title: "主机健康检查",
    summary: "快速检查系统、负载、磁盘、内存和失败服务。",
    category: "只读检查",
    fields: [],
    steps: [
      "uname -a",
      "uptime",
      "df -h",
      "free -h（如果不可用，改用系统对应的只读内存检查命令）",
      "systemctl --failed --no-pager（如果不是 systemd，说明并跳过）",
    ],
    success: ["列出主机基本信息和资源状态", "指出异常阈值或失败服务", "给出按优先级排序的下一步检查建议"],
  },
  {
    id: "service-troubleshoot",
    title: "服务故障排查",
    summary: "围绕一个 systemd 服务检查状态、日志和监听端口。",
    category: "服务排障",
    fields: [{ id: "service", label: "服务名", placeholder: "例如 nginx 或 docker" }],
    steps: [
      "systemctl status {{service}} --no-pager",
      "systemctl show {{service}} -p ActiveState,SubState,ExecMainStatus,MainPID",
      "journalctl -u {{service}} -n 100 --no-pager",
      "ss -lntup",
    ],
    success: ["确认服务当前状态和最近失败原因", "从日志中提取关键错误时间线", "核对服务可能使用的监听端口并给出下一步建议"],
  },
  {
    id: "log-triage",
    title: "日志快速分析",
    summary: "读取指定日志文件的最近内容，提取错误模式和时间线。",
    category: "日志排障",
    fields: [{ id: "path", label: "日志路径", placeholder: "例如 /var/log/syslog" }],
    steps: [
      "stat {{path}}",
      "tail -n 200 -- {{path}}",
      "grep -Ein 'error|fail|fatal|panic|timeout|denied' {{path}} | tail -n 100",
    ],
    success: ["先说明文件是否存在及最近修改时间", "按时间顺序归纳错误模式", "区分直接错误、伴随告警和需要验证的推断"],
  },
];

function shellQuote(value: string): string {
  return `'${value.trim().replace(/'/g, "'\\''")}'`;
}

function fieldValue(runbook: Runbook, values: Record<string, string>, id: string): string {
  const raw = values[id]?.trim() ?? "";
  const field = runbook.fields.find((item) => item.id === id);
  return field ? shellQuote(raw) : raw;
}

function interpolate(runbook: Runbook, values: Record<string, string>, text: string): string {
  return text.replace(/\{\{([^}]+)\}\}/g, (_match, id: string) => fieldValue(runbook, values, id));
}

export function buildRunbookPrompt(runbook: Runbook, values: Record<string, string>): string {
  const steps = runbook.steps
    .map((step, index) => `${index + 1}. ${interpolate(runbook, values, step)}`)
    .join("\n");
  const success = runbook.success.map((item) => `- ${item}`).join("\n");
  return `请按下面的运维 Runbook 逐步执行并汇报结果。

Runbook：${runbook.title}
目标：${runbook.summary}

执行约束：
- 这是只读排查流程，不要修改文件、重启服务、安装软件或清理数据。
- 每次只执行一个命令，先说明当前步骤，再读取退出码和输出。
- 命令不适用于当前系统时，记录原因并跳过，不要用替代命令掩盖失败。
- 不要猜测未提供的环境信息；发现高风险操作需求时先停止并说明。

步骤：
${steps}

完成标准：
${success}

最后请给出：事实、推断、未验证项和下一步建议。`;
}
