package com.example.kb.service;

import com.example.kb.model.DocumentEntity;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档摄取服务：上传 → 解析 → 切片 → 向量化 → 存入向量库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    /** 内存中的文档元数据（开发用，生产换成数据库）。 */
    private final Map<String, DocumentEntity> docMeta = new ConcurrentHashMap<>();

    @Value("${document.chunk.max-segment-size:500}")
    private int maxSegmentSize;

    @Value("${document.chunk.max-overlap-size:50}")
    private int maxOverlapSize;

    public String ingest(MultipartFile file) {
        String docId = UUID.randomUUID().toString().substring(0, 8);
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("kb-upload-", "-" + file.getOriginalFilename());
            file.transferTo(tempPath.toFile());

            // 解析文档
            Document document = parseDocument(tempPath, file.getOriginalFilename());
            log.info("Parsed document: {} ({} chars)", docId, document.text().length());

            // 切片 + 向量化 + 存储
            List<TextSegment> segments = split(document, docId);
            List<dev.langchain4j.data.embedding.Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            log.info("Indexed {} chunks for document: {}", segments.size(), docId);

            // 记录元数据
            String fileName = file.getOriginalFilename();
            docMeta.put(docId, DocumentEntity.builder()
                    .id(docId)
                    .fileName(fileName != null ? fileName : "unknown")
                    .fileType(getFileType(fileName))
                    .chunkCount(segments.size())
                    .createdAt(LocalDateTime.now())
                    .build());

            return docId;
        } catch (IOException e) {
            throw new RuntimeException("Failed to ingest document", e);
        } finally {
            if (tempPath != null) {
                try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
            }
        }
    }

    private Document parseDocument(Path path, String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return FileSystemDocumentLoader.loadDocument(path, new ApachePdfBoxDocumentParser());
        } else if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            return FileSystemDocumentLoader.loadDocument(path, new ApachePoiDocumentParser());
        } else {
            return FileSystemDocumentLoader.loadDocument(path);
        }
    }

    private List<TextSegment> split(Document document, String docId) {
        String text = document.text();
        List<TextSegment> segments = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxSegmentSize, text.length());
            String chunk = text.substring(start, end);
            segments.add(TextSegment.from(chunk, dev.langchain4j.data.document.Metadata
                    .from("docId", docId)
                    .put("chunkIndex", String.valueOf(segments.size()))));

            if (end == text.length()) break;

            int nextStart = end - maxOverlapSize;
            if (nextStart <= start) {
                start = end;  // 防止死循环：重叠会让 start 倒退时，直接跳到 end
            } else {
                start = nextStart;
            }
        }
        return segments;
    }

    public List<DocumentEntity> listDocuments() {
        return List.copyOf(docMeta.values());
    }

    public void remove(String docId) {
        docMeta.remove(docId);
        log.info("Remove document: {}", docId);
    }

    private String getFileType(String fileName) {
        if (fileName == null) return "unknown";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "word";
        if (lower.endsWith(".md") || lower.endsWith(".txt")) return "text";
        return "other";
    }
}