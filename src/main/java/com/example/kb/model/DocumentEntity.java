package com.example.kb.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 已索引文档的元数据模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {
    private String id;
    private String fileName;
    private String fileType;
    private int chunkCount;
    private LocalDateTime createdAt;
}