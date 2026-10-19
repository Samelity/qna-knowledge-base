package com.example.kb.controller;

import com.example.kb.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文档上传与管理接口。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档并索引。
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        String docId = documentService.ingest(file);
        return ResponseEntity.ok(Map.of("docId", docId, "status", "indexed"));
    }

    /**
     * 列出已索引文档。
     */
    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(documentService.listDocuments());
    }

    /**
     * 删除文档及其向量。
     */
    @DeleteMapping("/{docId}")
    public ResponseEntity<?> delete(@PathVariable String docId) {
        documentService.remove(docId);
        return ResponseEntity.noContent().build();
    }
}