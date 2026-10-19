package com.example.kb.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * RAG 问答服务：用户问题 → 检索相关片段 → 拼接 prompt → LLM 生成答案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatModel;

    private static final int RETRIEVAL_TOP_K = 5;

    /** 条款编号查询时的扩大召回量 */
    private static final int RETRIEVAL_TOP_K_EXPANDED = 20;

    /** 匹配"第X条"、"第X章"等编号查询 */
    private static final Pattern CLAUSE_PATTERN = Pattern.compile("第[零一二三四五六七八九十百千]+[条章节]");

    private static final PromptTemplate RAG_PROMPT = PromptTemplate.from(
            """
            你是一个企业知识库助手。请基于以下参考资料回答用户问题。
            如果资料确实完全不涉及用户所问，只需回复"未在知识库中找到相关信息"。

            ## 参考资料
            {{sources}}

            ## 用户问题
            {{question}}

            ## 回答要求
            1. 优先用资料中的内容回答，引用来源标为 [来源1][来源2] 等
            2. 如果资料提供了足够信息，即使分散在多段中，也请整合后给出完整回答
            """
    );

    public Answer answer(String question) {
        // 1. 问题向量化
        Embedding questionEmbedding = embeddingModel.embed(question).content();
        log.info("Question embedded, dimension: {}", questionEmbedding.dimension());

        // 2. 检测是否为条款编号查询，决定检索策略
        var clauseMatcher = CLAUSE_PATTERN.matcher(question);
        boolean isClauseQuery = clauseMatcher.find();
        String clauseKeyword = isClauseQuery ? clauseMatcher.group() : null;

        int topK = isClauseQuery ? RETRIEVAL_TOP_K_EXPANDED : RETRIEVAL_TOP_K;
        var results = embeddingStore.search(
                dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                        .queryEmbedding(questionEmbedding)
                        .maxResults(topK)
                        .minScore(isClauseQuery ? 0.3 : 0.5)
                        .build()
        );

        log.info("Retrieved {} matches (isClauseQuery={}, keyword={})",
                results.matches().size(), isClauseQuery, clauseKeyword);

        // 打印每个匹配片段的得分和前30个字
        results.matches().forEach(m ->
                log.info("  score={} textPreview={}", String.format("%.4f", m.score()),
                        m.embedded().text().substring(0, Math.min(30, m.embedded().text().length())))
        );

        // 条款查询：关键词二次过滤
        List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>> filtered;
        if (isClauseQuery && clauseKeyword != null) {
            filtered = new ArrayList<>();
            for (var match : results.matches()) {
                if (match.embedded().text().contains(clauseKeyword)) {
                    filtered.add(match);
                }
            }
            log.info("Keyword filter: {} → {} matches", results.matches().size(), filtered.size());
        } else {
            filtered = results.matches();
        }

        List<String> sources = filtered.stream()
                .map(m -> {
                    var meta = m.embedded().metadata();
                    return String.format("[%s chunk-%s]", meta.getString("docId"), meta.getString("chunkIndex"));
                })
                .toList();
        String context = filtered.stream()
                .map(m -> m.embedded().text())
                .reduce("", (a, b) -> a + "\n---\n" + b);

        log.info("Retrieved {} sources for question", filtered.size());

        // 3. 拼 prompt 并调用 LLM
        String prompt = RAG_PROMPT.apply(Map.of("sources", context, "question", question)).text();
        log.info("Sending prompt to LLM: {} chars (context: {} chars)",
                prompt.length(), context.length());
        String content = chatModel.generate(prompt);

        return Answer.builder()
                .content(content)
                .sources(sources)
                .build();
    }

    @Data
    @Builder
    public static class Answer {
        private String content;
        private List<String> sources;
    }
}