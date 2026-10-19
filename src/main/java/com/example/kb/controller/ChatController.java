package com.example.kb.controller;

import com.example.kb.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 问答接口：接收问题 → RAG 检索 → LLM 生成带来源的答案。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> ask(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        var answer = chatService.answer(question);
        return ResponseEntity.ok(Map.of(
                "question", question,
                "answer", answer.getContent(),
                "sources", answer.getSources()
        ));
    }
}