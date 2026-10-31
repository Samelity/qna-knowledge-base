package com.example.kb.controller;

import com.example.kb.model.DocumentEntity;
import com.example.kb.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        return ResponseEntity.ok(Map.of("success", true, "message", "deleted"));
    }

    /**
     * 获取原文件：默认 inline（PDF 可由浏览器直接内嵌预览），download=true 时作为附件下载。
     */
    @GetMapping("/{docId}/file")
    public ResponseEntity<Resource> file(@PathVariable String docId,
                                         @RequestParam(defaultValue = "false") boolean download) {
        DocumentEntity meta = documentService.getDocument(docId);
        Path path = documentService.resolveFile(meta);
        ContentDisposition disposition = ContentDisposition
                .builder(download ? "attachment" : "inline")
                .filename(meta.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType(meta.getFileType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(path));
    }

    /**
     * 提取纯文本：供 TXT/MD 及浏览器无法直接渲染的 Office 文档预览。
     */
    @GetMapping(value = "/{docId}/text", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> text(@PathVariable String docId) {
        return ResponseEntity.ok(documentService.extractText(docId));
    }

    private static MediaType mediaType(String fileType) {
        return switch (fileType == null ? "" : fileType.toLowerCase()) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "txt", "md" -> new MediaType("text", "plain", StandardCharsets.UTF_8);
            case "docx" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xlsx" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "pptx" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case "doc" -> MediaType.parseMediaType("application/msword");
            case "xls" -> MediaType.parseMediaType("application/vnd.ms-excel");
            case "ppt" -> MediaType.parseMediaType("application/vnd.ms-powerpoint");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}