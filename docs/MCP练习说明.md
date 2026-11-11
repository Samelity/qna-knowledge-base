# MCP 练习说明

把现有知识库问答系统作为 **MCP Server** 暴露出去，让 Claude Desktop、Qoder、MCP Inspector 等 MCP 客户端直接调用知识库能力。

## 1. 设计目标

- **不改变原有 Web 模式**：MCP 只是在同一个 Spring Boot 应用上多挂一个 `/mcp` Servlet，`/api/**` 与静态页面不受影响。
- **Streamable HTTP 传输**：与 Web 共用 8080 端口，无需独立进程、不重复初始化数据库和模型。
- **只读 tool**：第一阶段只暴露查询能力，不提供上传/删除等写操作。
- **可一键关闭**：`mcp.enabled=false` 时不注册任何 MCP 组件。

## 2. 整体架构

```
┌──────────────────────┐        ┌─────────────────────────────────────┐
│  MCP 客户端           │        │  Spring Boot 应用（8080）            │
│  Claude / Qoder /    │  HTTP  │                                     │
│  Inspector           │ ─────► │  /mcp   ── McpServerConfig          │
│                      │        │            └─ KbToolService         │
│                      │        │                 ├─ knowledge_answer │
│                      │        │                 └─ knowledge_search │
│                      │        │  /api/** ── 原有 Web 接口             │
│                      │        │  /       ── Web 界面                 │
└──────────────────────┘        └─────────────────────────────────────┘
                                         │
                        ChatService ─────┤
                         SearchService ──┼──► PGVector / Embedding / Qwen
                         DocumentService─┘
```

- 检索逻辑被抽取到 `SearchService`，Web 问答（`ChatService`）和 MCP tool 共用同一套策略（含"第X条"条款查询识别）。
- `knowledge_answer` 调用 `ChatService`，会消耗大模型 Token；`knowledge_search` 只做向量检索，不调用大模型。

## 3. 配置与启动

`src/main/resources/application.yml`：

```yaml
mcp:
  enabled: true       # false 时完全不注册 /mcp，普通 Web 模式照常运行
  endpoint: /mcp      # 路径必须与客户端配置一致
```

启动方式与原来相同：

```bash
mvn spring-boot:run
```

或直接在 IDE 中运行 `KnowledgeBaseApplication`。启动后 Web 界面仍是 http://localhost:8080，MCP 端点为 http://localhost:8080/mcp。

## 4. 客户端接入

### 4.1 Qoder / Cursor 等

在 MCP 配置中添加（不同客户端的字段名略有差异，按客户端文档选择 `url` 或 `type`）：

```json
{
  "mcpServers": {
    "qna-knowledge-base": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

### 4.2 Claude Code

```bash
claude mcp add --transport http qna-knowledge-base http://localhost:8080/mcp
```

### 4.3 MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

在界面中选择 **Streamable HTTP**，URL 填 `http://localhost:8080/mcp`，连接后可直接调用 `tools/list` 和 `tools/call`。

## 5. Tools

### knowledge_answer

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `question` | string | 是 | 用户问题 |

返回：大模型生成的答案 + `来源：[docId chunk-N]、...`。适合需要整合多段资料后给出结论的问题。

### knowledge_search

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `query` | string | 是 | 关键词或自然语言问题 |
| `topK` | integer | 否 | 返回片段数，1~50，默认 5 |

返回格式：

```
找到 2 个相关片段：

[1] 相似度=0.8421 文档=abc12345 片段=3
<片段正文>

[2] ...
```

失败时返回 `isError=true` 的文本结果，不会让整个 JSON-RPC 请求失败。

## 6. 手动验证协议流程（curl）

Streamable HTTP 的请求必须同时声明 `application/json` 和 `text/event-stream`。

### 6.1 初始化

```bash
curl -i -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"0.1"}}}'
```

记录响应头中的 `Mcp-Session-Id`。

### 6.2 通知初始化完成

```bash
curl -i -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <上一步的 session id>" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
```

### 6.3 列出 tools

```bash
curl -N -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <session id>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

### 6.4 调用检索 tool

```bash
curl -N -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <session id>" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"knowledge_search","arguments":{"query":"年终奖如何计算","topK":3}}}'
```

## 7. 代码地图

| 文件 | 职责 |
|---|---|
| `config/McpServerConfig.java` | 创建 Streamable HTTP transport、注册 Servlet、构建 `McpSyncServer`；受 `mcp.enabled` 控制 |
| `mcp/KbToolService.java` | 定义 `knowledge_answer` / `knowledge_search` 的 JSON Schema 与调用逻辑 |
| `service/SearchService.java` | 向量化 + 条款识别 + 检索 + 关键词二次过滤，Web 与 MCP 共用 |
| `service/ChatService.java` | RAG 问答，复用 `SearchService` |
| `pom.xml` | MCP SDK 2.1.0；显式对齐 reactor-core 3.7.0 与 jackson-annotations 2.21 |

依赖说明：MCP SDK 2.1.0 默认使用 Jackson 3（`tools.jackson.*`）做协议序列化，与 Spring Boot 3.3 使用的 Jackson 2（`com.fasterxml.jackson.*`）包名不同，可以并存。`jackson-annotations` 被显式定为 2.21，满足 Jackson 3 的编译期要求；`reactor-core` 被显式定为 3.7.0，避免被 Boot 3.3 的 reactor-bom 降级到 3.6。

## 8. 练习任务

### 任务 1：跑通链路（入门）

1. 启动应用，用 MCP Inspector 连接 `http://localhost:8080/mcp`。
2. 调用 `tools/list`，确认能看到两个 tool 及其 inputSchema。
3. 调用 `knowledge_search` 检索一个已上传文档中的关键词，检查相似度和来源标识。

### 任务 2：新增第三个 tool（进阶）

仿照 `KbToolService.searchTool()` 新增 `knowledge_documents`，列出当前知识库中的文档。可直接复用 `DocumentService.listDocuments()`。

需要思考：
- 返回纯文本还是 `structuredContent`？
- 文档集合可能很大，要不要分页参数？
- 这个 tool 是否应该标记为只读（`ToolAnnotations.readOnlyHint`）？

### 任务 3：参数配置化（进阶）

把 `MAX_TOP_K`、默认 `topK` 改为从 `application.yml` 读取，并在 tool 的 description 中体现当前上限。

### 任务 4：评估开放范围（思考题）

- 当前 `/mcp` 没有任何鉴权。若把应用部署到内网共享机器，暴露知识库内容和 Token 消耗的风险是什么？
- 如果第二阶段要加"上传文档"tool，需要哪些防护（文件类型、大小、路径、并发索引）？

## 9. 排错

| 现象 | 排查方向 |
|---|---|
| 客户端连不上 / 404 | 确认 `mcp.enabled=true`；URL 必须是 `.../mcp`（SDK 用 `requestURI.endsWith(endpoint)` 校验） |
| 400 `text/event-stream required` | 手动 curl 时 `Accept` 必须同时包含 `application/json` 和 `text/event-stream` |
| 401/403 类错误 | 检查客户端是否正确携带 `Mcp-Session-Id` |
| tool 不出现在客户端 | 重启客户端；确认 `McpServer` 构建时 `tools(...)` 已注册，日志中无启动异常 |
| 看不到检索日志 | `application.yml` 中 `logging.level.com.example.kb: DEBUG`；需要 MCP SDK 日志时加 `logging.level.io.modelcontextprotocol: DEBUG` |
| 启动报 NoSuchMethodError（reactor/Jackson） | 检查是否有人移除了 `pom.xml` 中对 reactor-core / jackson-annotations 的版本对齐 |

## 10. 当前状态

已完成：依赖接入、检索逻辑抽取、两个 MCP tool、Servlet 注册、`mvn clean compile` 零警告通过。

**尚未验证（需要本地启动后确认）**：

- 应用能否正常启动（需要 PostgreSQL + pgvector、`PG_PASSWORD`、`DASHSCOPE_API_KEY`）。
- MCP 客户端 initialize / tools/list / tools/call 全链路。
- Jackson 3 与 Spring Boot 3.3 的 Jackson 2 在同一进程内的运行时共存。
- reactor-core 3.7.0 与 Spring Boot 3.3 的运行时兼容性。
