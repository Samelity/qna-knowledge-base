# 企业知识库问答系统

基于 RAG（检索增强生成）的企业知识库问答系统，支持文档上传、智能切分、向量检索和大模型问答。

## 技术栈

- **后端框架**: Spring Boot 3.3.5 + Java 17
- **AI 能力**: LangChain4j 0.35.0
- **大模型**: 通义千问 (qwen-plus)
- **向量模型**: text-embedding-v2
- **向量数据库**: PostgreSQL + PGVector
- **文档解析**: Apache PDFBox (PDF) + Apache POI (DOCX)
- **构建工具**: Maven

## 功能特性

- **文档管理**: 支持上传 PDF、DOCX、TXT、MD 格式
- **智能切片**: 自动将文档切分为片段，支持重叠窗口
- **向量检索**: 基于语义相似度的智能检索
- **条款查询优化**: 自动识别"第X条"等条款编号，扩大召回量并二次过滤
- **问答生成**: 基于检索结果生成带来源引用的答案
- **Token 统计**: 自动记录每次问答的 Token 消耗
- **Web 界面**: 提供简洁的文档管理和问答交互界面
- **日志管理**: 分级日志输出（INFO/ERROR），支持按天归档

## 前置要求

- JDK 17+
- PostgreSQL 12+ (需安装 pgvector 扩展)
- 通义千问 API Key

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/Samelity/qna-knowledge-base.git
cd qna-knowledge-base
```

### 2. 配置环境变量

设置以下环境变量：

```bash
export DASHSCOPE_API_KEY=your_api_key_here
export PG_PASSWORD=your_postgres_password
```

或在 IDE 运行配置中直接设置环境变量。

### 3. 初始化数据库

创建 PostgreSQL 数据库并启用 pgvector 扩展：

```sql
CREATE DATABASE kbvector;

\c kbvector

CREATE EXTENSION IF NOT EXISTS vector;
```

### 4. 运行项目

```bash
mvn spring-boot:run
```

应用启动后访问：http://localhost:8080

## 配置说明

主要配置项位于 `src/main/resources/application.yml`：

```yaml
# 服务端口
server:
  port: 8080

# 数据库配置
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/kbvector
    username: postgres
    password: ${PG_PASSWORD}

# 通义千问配置
langchain4j:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY}
    chat-model:
      model-name: qwen-plus
      temperature: 0.3
      max-tokens: 2048

# 文档切片参数
document:
  chunk:
    max-segment-size: 500    # 最大片段字符数
    max-overlap-size: 50     # 重叠字符数

# 检索参数
rag:
  retrieval:
    top-k: 5                 # 默认召回数量
    min-score: 0.6           # 最小相似度阈值

# 向量数据库配置
vector:
  pg:
    host: localhost
    port: 5432
    database: kbvector
    username: postgres
    password: ${PG_PASSWORD}
    table-name: document_embedding
```

## API 接口

### 文档管理

#### 上传文档
```http
POST /api/documents/upload
Content-Type: multipart/form-data

file: <文件>
```

响应：
```json
{
  "docId": "abc12345",
  "status": "indexed"
}
```

#### 获取文档列表
```http
GET /api/documents
```

响应：
```json
[
  {
    "id": "abc12345",
    "fileName": "测试文档.pdf",
    "fileType": "pdf",
    "chunkCount": 12,
    "createdAt": "2026-01-15T10:30:00"
  }
]
```

#### 删除文档
```http
DELETE /api/documents/{docId}
```

响应：
```json
{
  "success": true,
  "message": "deleted"
}
```

### 问答接口

#### 提问
```http
POST /api/chat
Content-Type: application/json

{
  "question": "你的问题"
}
```

响应：
```json
{
  "question": "你的问题",
  "answer": "AI 生成的答案",
  "sources": [
    "[abc12345 chunk-0]",
    "[abc12345 chunk-3]"
  ]
}
```

## 项目结构

```
qna-knowledge-base/
├── src/main/java/com/example/kb/
│   ├── KnowledgeBaseApplication.java    # 启动类
│   ├── config/
│   │   └── RagConfig.java               # RAG 核心配置
│   ├── controller/
│   │   ├── ChatController.java          # 问答接口
│   │   └── DocumentController.java      # 文档管理接口
│   ├── service/
│   │   ├── ChatService.java             # 问答服务（RAG 流程）
│   │   └── DocumentService.java         # 文档摄取服务
│   ├── model/
│   │   └── DocumentEntity.java          # 文档元数据实体
│   ├── token/
│   │   ├── ChatTokenListener.java       # Token 使用监听
│   │   ├── TokenUsageAggregator.java    # Token 统计聚合
│   │   └── KbRequestContext.java        # 请求上下文
│   └── exception/
│       ├── BusinessException.java       # 业务异常
│       └── GlobalExceptionHandler.java  # 全局异常处理
├── src/main/resources/
│   ├── application.yml                  # 应用配置
│   ├── logback-spring.xml               # 日志配置
│   └── static/
│       └── index.html                   # Web 界面
├── pom.xml                              # Maven 配置
└── test-requests.http                   # API 测试用例
```

## 核心流程

### 文档摄取流程
1. 上传文件 → 大小和格式校验
2. 文件落盘（UUID 命名，避免中文/特殊字符问题）
3. 解析文档内容（PDF/DOCX/TXT）
4. 智能切片（按字符数切分，支持重叠）
5. 向量化切片（调用 Embedding 模型）
6. 存入 PGVector 向量数据库
7. 元数据持久化到 PostgreSQL

### 问答流程
1. 用户提问 → 问题向量化
2. 检测是否为条款查询（如"第X条"）
3. 向量检索（条款查询扩大召回量）
4. 条款查询时进行关键词二次过滤
5. 拼接 Prompt（参考资料 + 用户问题）
6. 调用大模型生成答案
7. 返回答案和来源引用
8. 记录 Token 消耗

## 日志管理

日志输出到以下位置：
- **控制台**: 所有日志
- **logs/kb-info.log**: INFO 及以上级别，按天归档，保留 30 天
- **logs/kb-error.log**: 仅 ERROR 级别，按天归档，保留 30 天

Token 消耗日志格式：
```
[CHAT-TOKEN] reqId=xxx prompt=123 completion=456 total=579
```

## 常见问题

### 1. JVM 警告：Sharing is only supported for boot loader classes
这是 Spring Boot 3.x + JDK 17 的正常警告，不影响功能，可以忽略。

### 2. 上传 .doc 文件失败
系统不支持旧版 .doc 格式，请先另存为 .docx 再上传。

### 3. 数据库连接失败
- 检查 PostgreSQL 是否启动
- 确认 pgvector 扩展已安装
- 验证环境变量 `PG_PASSWORD` 是否正确

### 4. API Key 无效
确认环境变量 `DASHSCOPE_API_KEY` 已正确设置且有效。

## 开发计划

- [ ] 支持更多文档格式（Excel、PPT）
- [ ] 添加文档预览功能
- [ ] 支持多轮对话
- [ ] 知识库分类管理
- [ ] 用户权限控制
- [ ] 问答历史记录
- [ ] 导出问答报告
