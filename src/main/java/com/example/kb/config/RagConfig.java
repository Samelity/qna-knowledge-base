package com.example.kb.config;

import com.example.kb.token.ChatTokenListener;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RAG 核心配置：Chat + Embedding + 向量存储。
 */
@Slf4j
@Configuration
public class RagConfig {

    @Value("${vector.pg.host:localhost}")
    private String host;

    @Value("${vector.pg.port:5432}")
    private Integer port;

    @Value("${vector.pg.username:postgres}")
    private String user;

    @Value("${vector.pg.password:your_password}")
    private String password;

    @Value("${vector.pg.database:kb_vector}")
    private String database;

    @Value("${vector.pg.table-name:document_embedding}")
    private String tableName;

    @Bean
    public ChatLanguageModel chatLanguageModel(ChatTokenListener listener) {
        return QwenChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .temperature(0.3f)
                .listeners(List.of(listener))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return QwenEmbeddingModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("text-embedding-v2")
                .build();
    }

    /**
     * PGVector 向量持久化配置（适配 langchain4j 0.35）。
     * 作用：用 PgVectorEmbeddingStore 替换原来注入的 InMemoryEmbeddingStore，
     * 向量数据持久化进 PostgreSQL + pgvector 扩展，重启不丢失，并支持按向量 ID 精确删除。
     * 注意：维度直接取自 embeddingModel.dimension()，保证与向量模型一致，
     * 不会因 dimension 配错导致 INSERT 报错。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel) {
        int dimension = embeddingModel.dimension();
        log.info("Init PgVectorEmbeddingStore: table={}, dimension={}", tableName, dimension);
        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .user(user)
                .password(password)
                .database(database)
                .table(tableName)
                .dimension(dimension)
                .createTable(true)
                .dropTableFirst(false)
                .build();
    }
}