package com.example.kb.controller;

import com.example.kb.service.ChatService;
import com.example.kb.token.KbRequestContext;
import com.example.kb.token.TokenUsageAggregator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 问答接口：接收问题 → RAG 检索 → LLM 生成带来源的答案。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> ask(@RequestBody Map<String, String> request) {
        String reqId = TokenUsageAggregator.newRequestId();
        KbRequestContext.set(reqId);

        String question = request.get("question");
        var answer = chatService.answer(question);

        TokenUsageAggregator.Bucket bucket = TokenUsageAggregator.finish(reqId);
        log.info("[CHAT-TOKEN] reqId={} prompt={} completion={} total={}",
                reqId,
                bucket.promptTokens(),
                bucket.completionTokens(),
                bucket.totalTokens());

        return ResponseEntity.ok(Map.of(
                "question", question,
                "answer", answer.getContent(),
                "sources", answer.getSources()
        ));
    }
}