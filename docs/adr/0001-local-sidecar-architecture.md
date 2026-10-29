# 本地 sidecar 架构与嵌入式数据库

桌面工具采用 Tauri 启动本机 Spring Boot sidecar（localhost HTTP/WebSocket），SSH 连接由用户机器直接发起，存储使用 SQLite 嵌入式库而不是 PostgreSQL。**Why**：单用户桌面场景下，SSH 与用户日常终端走同一网络路径，凭据不出本机，且免去安装数据库服务；远端部署与 PG 留到多用户/团队形态再考虑。**后果**：发布包需携带裁剪 JRE（jlink/jpackage）；迁移脚本保持方言中立，以便将来切换 PG。
