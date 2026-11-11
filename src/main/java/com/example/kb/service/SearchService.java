package com.example.kb.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 向量检索服务：把"问题向量化 + 条款查询识别 + 相似度检索"从问答链路中独立出来，
 * 供 Web 问答与 MCP tool 共用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    private static final int DEFAULT_TOP_K = 5;
    private static final int CLAUSE_TOP_K = 20;
    private static final double DEFAULT_MIN_SCORE = 0.5;
    private static final double CLAUSE_MIN_SCORE = 0.3;

    /** 匹配"第X条"、"第X章"等编号查询 */
    private static final Pattern CLAUSE_PATTERN = Pattern.compile("第[零一二三四五六七八九十百千]+[条章节]");

    public List<SearchHit> search(String query) {
        return search(query, 0);
    }

    /**
     * @param maxResults 显式召回量；<= 0 时沿用默认策略（条款查询 20，其余 5）
     */
    public List<SearchHit> search(String query, int maxResults) {
        Embedding questionEmbedding = embeddingModel.embed(query).content();
        log.info("Question embedded, dimension: {}", questionEmbedding.dimension());

        var clauseMatcher = CLAUSE_PATTERN.matcher(query);
        boolean isClauseQuery = clauseMatcher.find();
        String clauseKeyword = isClauseQuery ? clauseMatcher.group() : null;

        int topK = maxResults > 0 ? maxResults : (isClauseQuery ? CLAUSE_TOP_K : DEFAULT_TOP_K);
        var results = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(questionEmbedding)
                        .maxResults(topK)
                        .minScore(isClauseQuery ? CLAUSE_MIN_SCORE : DEFAULT_MIN_SCORE)
                        .build()
        );

        log.info("Retrieved {} matches (isClauseQuery={}, keyword={})",
                results.matches().size(), isClauseQuery, clauseKeyword);
        results.matches().forEach(m ->
                log.info("  score={} textPreview={}", String.format("%.4f", m.score()),
                        m.embedded().text().substring(0, Math.min(30, m.embedded().text().length())))
        );

        List<EmbeddingMatch<TextSegment>> filtered;
        if (isClauseQuery && clauseKeyword != null) {
            filtered = new ArrayList<>();
            for (var match : results.matches()) {
                if (match.embedded().text().contains(clauseKeyword)) {
                    filtered.add(match);
                }
            }
            log.info("Keyword filter: {} -> {} matches", results.matches().size(), filtered.size());
        } else {
            filtered = results.matches();
        }

        return filtered.stream()
                .map(m -> {
                    var meta = m.embedded().metadata();
                    return SearchHit.builder()
                            .text(m.embedded().text())
                            .score(m.score())
                            .docId(meta.getString("docId"))
                            .chunkIndex(meta.getString("chunkIndex"))
                            .build();
                })
                .toList();
    }

    @Data
    @Builder
    public static class SearchHit {
        private String text;
        private double score;
        private String docId;
        private String chunkIndex;
    }
}
