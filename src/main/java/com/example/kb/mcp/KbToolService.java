package com.example.kb.mcp;

import com.example.kb.service.ChatService;
import com.example.kb.service.SearchService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tool 定义：把知识库能力以只读 tool 的形式暴露给 MCP 客户端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbToolService {

    private static final int MAX_TOP_K = 50;

    private final ChatService chatService;
    private final SearchService searchService;

    public List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        return List.of(answerTool(), searchTool());
    }

    private McpServerFeatures.SyncToolSpecification answerTool() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of(
                                "type", "string",
                                "description", "用户问题，建议使用完整自然的问句"
                        )
                ),
                "required", List.of("question")
        );
        McpSchema.Tool tool = McpSchema.Tool.builder("knowledge_answer", inputSchema)
                .description("基于企业知识库回答问题，返回答案和引用的来源片段标识。"
                        + "适合需要整合多段资料后给出结论的问题；回答只依据知识库内容。")
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String question = stringArg(request, "question");
                    if (question == null || question.isBlank()) {
                        return error("question 不能为空");
                    }
                    try {
                        ChatService.Answer answer = chatService.answer(question);
                        String text = answer.getContent();
                        if (answer.getSources() != null && !answer.getSources().isEmpty()) {
                            text += "\n\n来源：" + String.join("、", answer.getSources());
                        }
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(text)
                                .build();
                    } catch (Exception e) {
                        log.error("MCP tool knowledge_answer failed: question={}", question, e);
                        return error("知识库问答失败: " + e.getMessage());
                    }
                })
                .build();
    }

    private McpServerFeatures.SyncToolSpecification searchTool() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "检索语句，可以是关键词或自然语言问题"
                        ),
                        "topK", Map.of(
                                "type", "integer",
                                "description", "返回的最大片段数，默认 5，最大 50",
                                "minimum", 1,
                                "maximum", MAX_TOP_K
                        )
                ),
                "required", List.of("query")
        );
        McpSchema.Tool tool = McpSchema.Tool.builder("knowledge_search", inputSchema)
                .description("在知识库中按语义检索相关文档片段，返回片段内容、相似度和来源标识，不调用大模型。"
                        + "适合先查看原始资料，或自行判断检索结果是否相关。")
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String query = stringArg(request, "query");
                    if (query == null || query.isBlank()) {
                        return error("query 不能为空");
                    }
                    int topK = resolveTopK(request);
                    try {
                        List<SearchService.SearchHit> hits = searchService.search(query, topK);
                        if (hits.isEmpty()) {
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("未在知识库中检索到相关片段。")
                                    .build();
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("找到 ").append(hits.size()).append(" 个相关片段：");
                        for (int i = 0; i < hits.size(); i++) {
                            SearchService.SearchHit hit = hits.get(i);
                            sb.append("\n\n[").append(i + 1).append("] 相似度=")
                                    .append(String.format("%.4f", hit.getScore()))
                                    .append(" 文档=").append(hit.getDocId())
                                    .append(" 片段=").append(hit.getChunkIndex())
                                    .append('\n').append(hit.getText());
                        }
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(sb.toString())
                                .build();
                    } catch (Exception e) {
                        log.error("MCP tool knowledge_search failed: query={}", query, e);
                        return error("知识库检索失败: " + e.getMessage());
                    }
                })
                .build();
    }

    private static String stringArg(McpSchema.CallToolRequest request, String name) {
        if (request.arguments() == null) {
            return null;
        }
        Object value = request.arguments().get(name);
        return value == null ? null : value.toString();
    }

    private static int resolveTopK(McpSchema.CallToolRequest request) {
        if (request.arguments() == null) {
            return 0;
        }
        Object value = request.arguments().get("topK");
        int topK;
        if (value instanceof Number number) {
            topK = number.intValue();
        } else if (value != null) {
            try {
                topK = Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        } else {
            return 0;
        }
        return Math.max(1, Math.min(MAX_TOP_K, topK));
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }
}
