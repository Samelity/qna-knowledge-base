package com.example.kb.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档元数据。相比旧版新增两个字段，用于支撑 PGVector 持久化后的精确删除：
 * - storedFileName：落盘物理文件名（UUID.docx 等），删除文档时同步删物理文件；
 * - embeddingIds：该文档所有切片在向量表中的向量主键，remove() 时按这些 ID 精确删向量。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    private String id;
    private String fileName;              // 展示名，保留原始中文
    private String fileType;
    private int chunkCount;
    private LocalDateTime createdAt;

    /**
     * 落盘物理文件名（UUID.ext），用于删除时定位并删除上传的原始文件。
     */
    private String storedFileName;

    /**
     * 该文档切片写入 PGVector 后返回的向量 ID 列表，用于精确删除向量。
     */
    private List<String> embeddingIds;
}