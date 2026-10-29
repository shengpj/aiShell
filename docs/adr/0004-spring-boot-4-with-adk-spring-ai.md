# Spring Boot 4 + 官方 Spring AI 桥接 ADK

后端采用 Spring Boot 4.0.2 / JDK 21 / Maven 3.9.14，用 `com.google.adk:google-adk-spring-ai:1.13.0`（Spring AI 2.0.1）把 ADK 的 `BaseLlm` 接到 Spring AI 的 OpenAI 兼容 `ChatModel` 上。**Why**：要保留 Spring AI 且使用官方 ADK 桥，而该桥只在 Boot 4 上可用；自写 `BaseLlm` 适配或改走 LangChain4j 都会放弃官方支持。**后果**：偏离最初指定的 Spring Boot 3 与 JDK 17/Maven 3.8.x（2026-10-27 确认改用 JDK 21 + Maven 3.9.14）；该模块不支持 live connection，`topK`/response schema 不映射。

S0 spike 用假 `ChatModel` 离线验证了桥的行为：ADK 负责工具执行（Spring AI 2.0.1 的 OpenAI 模型不自行执行工具）；工具名就是 Java 方法名（`getServerTime`），模型必须用这个精确名字，否则 ADK 报 `Tool not found` 并无限重试；流式按 chunk 逐事件下发，客户端需要拼接包括最后一个非 partial 事件在内的所有文本。真实链路也已验证：OpenAI 兼容网关 `https://sub.vcnovb.cn` + `deepseek-v4.1-flash` 下，流式分片、FunctionTool 往返和多轮追问全部通过。
