package com.example.kb.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG 问答服务：用户问题 → 检索相关片段 → 拼接 prompt → LLM 生成答案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final SearchService searchService;
    private final ChatLanguageModel chatModel;

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
        List<SearchService.SearchHit> hits = searchService.search(question);

        List<String> sources = hits.stream()
                .map(h -> String.format("[%s chunk-%s]", h.getDocId(), h.getChunkIndex()))
                .toList();
        String context = hits.stream()
                .map(SearchService.SearchHit::getText)
                .reduce("", (a, b) -> a + "\n---\n" + b);

        log.info("Retrieved {} sources for question", hits.size());

        // 拼 prompt 并调用 LLM
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
