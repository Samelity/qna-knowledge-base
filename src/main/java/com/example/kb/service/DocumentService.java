package com.example.kb.service;

import com.example.kb.exception.BusinessException;
import com.example.kb.model.DocumentEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文档摄取服务：上传 -> 解析 -> 切片 -> 向量化 -> 存入 PGVector 向量库。
 *
 * 1) 文档元数据不再放内存 Map，而是持久化到业务库 PostgreSQL 的 document_meta 表，
 *    重启后文档清单不丢；
 * 2) listDocuments() / 删除等接口改为直接读写数据库；
 * 3) addAll() 返回的向量 ID 列表序列化后存 embedding_ids 字段，
 *    删除时按 ID 精确删 PGVector 向量；
 * 4) 落盘物理文件名存 stored_file_name，删除时一并清理；
 * 5) remove() 真删除：先按库中记录的向量 ID 删 PGVector 向量 -> 删物理文件 -> 删数据库行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    /** 业务库（spring.datasource 指向的 PostgreSQL），用于持久化文档元数据 */
    private final JdbcTemplate jdbcTemplate;
    /** 复用 Spring 容器里的 ObjectMapper，用于 embeddingIds 的 JSON 序列化 */
    private final ObjectMapper objectMapper;

    private static final String TABLE = "document_meta";

    private static final Set<String> ALLOWED_EXT =
            Set.of("pdf", "docx", "xlsx", "xls", "pptx", "ppt", "txt", "md");

    @Value("${document.chunk.max-segment-size:500}")
    private int maxSegmentSize;

    @Value("${document.chunk.max-overlap-size:50}")
    private int maxOverlapSize;

    @Value("${document.upload.max-size-bytes:10485760}")
    private long maxFileSizeBytes;

    @Value("${document.upload.dir:./uploads}")
    private String uploadDir;

    /** document_meta -> DocumentEntity 的行映射 */
    private final RowMapper<DocumentEntity> rowMapper = (rs, rowNum) -> DocumentEntity.builder()
            .id(rs.getString("id"))
            .fileName(rs.getString("file_name"))
            .fileType(rs.getString("file_type"))
            .chunkCount(rs.getInt("chunk_count"))
            .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
            .storedFileName(rs.getString("stored_file_name"))
            .embeddingIds(readEmbeddingIds(rs.getString("embedding_ids")))
            .build();

    public String ingest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "上传文件为空");
        }

        // 安全项：文件大小上限校验，避免大文件 OOM
        if (file.getSize() > maxFileSizeBytes) {
            throw new BusinessException("FILE_TOO_LARGE",
                    String.format("文件过大，最大允许 %d MB", maxFileSizeBytes / 1024 / 1024));
        }

        // 先校验并规范化扩展名，杜绝把原始文件名直接拼进临时文件名（防注入 / InvalidPathException）
        String ext = normalizeExtension(file.getOriginalFilename());

        String docId = UUID.randomUUID().toString().substring(0, 8);
        // 物理文件统一以完整 UUID 命名落盘：彻底规避中文/空格/特殊字符导致的编码乱码与重名冲突
        String savedName = UUID.randomUUID() + "." + ext;
        Path target = Path.of(uploadDir).toAbsolutePath().normalize().resolve(savedName);
        boolean indexed = false;
        try {
            // 落盘目录自动创建；原始中文名不进入物理文件名
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
            log.info("Saved physical file: {} (original: {})", savedName, file.getOriginalFilename());

            // 解析文档
            Document document = parseDocument(target, ext);
            String text = document.text();

            // 空文档（空 PDF / 扫描件 / 解析失败）会在后续 embedAll 抛异常，提前判空兜底
            if (text == null || text.isBlank()) {
                throw new BusinessException("EMPTY_CONTENT", "文档无可解析文本，可能是空文件或扫描图片型 PDF");
            }
            log.info("Parsed document: {} ({} chars)", docId, text.length());

            // 切片 + 向量化 + 存储
            List<TextSegment> segments = split(document, docId);
            if (segments.isEmpty()) {
                throw new BusinessException("EMPTY_CONTENT", "文档切片结果为空，无法向量化");
            }
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 【PGVector】addAll 返回写入向量表的向量主键列表，记录下来供精确删除
            List<String> embeddingIds = embeddingStore.addAll(embeddings, segments);
            log.info("Indexed {} chunks into PGVector for document: {}", segments.size(), docId);

            // 记录元数据：物理名用 UUID，展示名保留原始中文名；同时记录向量 ID 供删除
            DocumentEntity entity = DocumentEntity.builder()
                    .id(docId)
                    .fileName(displayName(file.getOriginalFilename()))
                    .fileType(ext)
                    .chunkCount(segments.size())
                    .createdAt(LocalDateTime.now())
                    .storedFileName(savedName)
                    .embeddingIds(embeddingIds)
                    .build();

            // 【持久化】元数据写入业务库 document_meta，重启后清单不丢
            saveMeta(entity);

            indexed = true;
            return docId;
        } catch (IOException e) {
            throw new BusinessException("INGEST_FAILED", "文档摄取失败: " + e.getMessage(), e);
        } finally {
            // 摄取失败时清理已落盘的残留文件，避免脏数据堆积；成功则持久化保留
            if (!indexed) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 规范化并校验扩展名：取 basename、过滤非法字符、强制小写、命中白名单。
     * .doc 老格式因 ApachePoiDocumentParser 支持不稳，明确拒绝。
     */
    private String normalizeExtension(String originalName) {
        String base = basename(originalName).toLowerCase();
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            throw new BusinessException("UNSUPPORTED_TYPE",
                    "无法识别文件类型，请上传 pdf/docx/xlsx/xls/pptx/ppt/txt/md");
        }
        String ext = base.substring(dot + 1);
        if ("doc".equals(ext)) {
            throw new BusinessException("UNSUPPORTED_TYPE",
                    "暂不支持 .doc 老格式，请先另存为 .docx 再上传");
        }
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException("UNSUPPORTED_TYPE", "不支持的文件类型: ." + ext);
        }
        return ext;
    }

    /**
     * 仅保留文件名 basename，去掉任何路径分隔符，防止目录穿越 / 非法字符。
     */
    private String basename(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException("INVALID_NAME", "文件名缺失");
        }
        String name = originalName.replace('\\', '/');
        int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        // 过滤其余非法/控制字符
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank()) {
            throw new BusinessException("INVALID_NAME", "文件名清洗后为空");
        }
        return name;
    }

    /**
     * 展示用文件名：仅去掉路径分隔符与危险字符，保留中文等 Unicode。
     */
    private String displayName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "unknown";
        }
        String name = originalName.replace('\\', '/');
        int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        // 只替换可能引发路径/解析问题的危险字符，中文原样保留
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isBlank() ? "unknown" : name;
    }

    private Document parseDocument(Path path, String ext) {
        try {
            return switch (ext) {
                case "pdf" -> FileSystemDocumentLoader.loadDocument(path, new ApachePdfBoxDocumentParser());
                // POI 经 ExtractorFactory 统一处理 docx / xlsx / xls / pptx / ppt
                case "docx", "xlsx", "xls", "pptx", "ppt" ->
                        FileSystemDocumentLoader.loadDocument(path, new ApachePoiDocumentParser());
                default ->
                    // 显式 UTF-8，避免 GBK 等编码的 txt 乱码
                        FileSystemDocumentLoader.loadDocument(path, new TextDocumentParser(StandardCharsets.UTF_8));
            };
        } catch (BlankDocumentException e) {
            throw new BusinessException("EMPTY_CONTENT", "文档无可解析文本，可能是空文件或扫描图片型文档");
        } catch (RuntimeException e) {
            throw new BusinessException("PARSE_FAILED",
                    "文档解析失败，请确认文件未损坏或未加密: " + e.getMessage(), e);
        }
    }

    private List<TextSegment> split(Document document, String docId) {
        String text = document.text();
        List<TextSegment> segments = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxSegmentSize, text.length());
            String chunk = text.substring(start, end);
            segments.add(TextSegment.from(chunk, Metadata
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

    /**
     * 【持久化查询】文档清单改为读业务库，重启后不再丢失。
     * 按创建时间倒序返回。
     */
    public List<DocumentEntity> listDocuments() {
        String sql = "SELECT id, file_name, file_type, chunk_count, created_at, "
                + "stored_file_name, embedding_ids FROM " + TABLE
                + " ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    /**
     * 【持久化查询】按 ID 读单条元数据。
     */
    public DocumentEntity getDocument(String docId) {
        String sql = "SELECT id, file_name, file_type, chunk_count, created_at, "
                + "stored_file_name, embedding_ids FROM " + TABLE + " WHERE id = ?";
        List<DocumentEntity> found = jdbcTemplate.query(sql, rowMapper, docId);
        if (found.isEmpty()) {
            throw new BusinessException("NOT_FOUND", "文档不存在: " + docId);
        }
        return found.get(0);
    }

    /**
     * 删除文档：按库中记录的向量 ID 精确删除 PGVector 中的向量 -> 删物理文件 -> 删数据库行。
     * 这是 PGVector 相对 InMemoryEmbeddingStore 的核心收益：向量终于可以真正删掉了。
     */
    public void remove(String docId) {
        // 先从业务库读出元数据（拿到向量 ID 与物理文件名）
        DocumentEntity meta = getDocument(docId);

        // 1) 精确删除该文档的全部向量
        List<String> embeddingIds = meta.getEmbeddingIds();
        if (embeddingIds != null && !embeddingIds.isEmpty()) {
            embeddingStore.removeAll(embeddingIds);
            log.info("Deleted {} vectors from PGVector for document: {}", embeddingIds.size(), docId);
        }

        // 2) 删除落盘的物理文件
        String storedFileName = meta.getStoredFileName();
        if (storedFileName != null && !storedFileName.isBlank()) {
            try {
                Path physical = Path.of(uploadDir).toAbsolutePath().normalize().resolve(storedFileName);
                Files.deleteIfExists(physical);
                log.info("Deleted physical file: {} for document: {}", storedFileName, docId);
            } catch (IOException e) {
                log.warn("Failed to delete physical file {} for document {}: {}",
                        storedFileName, docId, e.getMessage());
            }
        }

        // 3) 删除业务库中的元数据行
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE id = ?", docId);

        log.info("Document fully removed: {}", docId);
    }

    // ---------------- 持久化辅助方法 ----------------

    /** 把一条文档元数据写入 document_meta 表。 */
    private void saveMeta(DocumentEntity e) {
        String sql = "INSERT INTO " + TABLE
                + " (id, file_name, file_type, chunk_count, created_at, stored_file_name, embedding_ids)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                e.getId(),
                e.getFileName(),
                e.getFileType(),
                e.getChunkCount(),
                Timestamp.valueOf(e.getCreatedAt()),
                e.getStoredFileName(),
                writeEmbeddingIds(e.getEmbeddingIds()));
    }

    /** embeddingIds(List<String>) 序列化为 JSON 字符串存库。 */
    private String writeEmbeddingIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception ex) {
            log.warn("Serialize embeddingIds failed, store empty: {}", ex.getMessage());
            return "[]";
        }
    }

    /** 库中 JSON 字符串反序列化为 List<String>。 */
    private List<String> readEmbeddingIds(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            log.warn("Deserialize embeddingIds failed: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}