package com.example.kb.config;

import com.example.kb.mcp.KbToolService;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 配置：在现有 Web 应用上额外挂载一个 Streamable HTTP 端点，
 * 不影响原有 REST 接口与静态页面。mcp.enabled=false 时完全不注册。
 */
@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    private static final String SERVER_NAME = "qna-knowledge-base";
    private static final String SERVER_VERSION = "0.1.0";

    @Bean
    public HttpServletStreamableServerTransportProvider mcpStreamableTransportProvider(
            @Value("${mcp.endpoint:/mcp}") String endpoint) {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(normalizePath(endpoint))
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transportProvider,
            @Value("${mcp.endpoint:/mcp}") String endpoint) {
        String path = normalizePath(endpoint);
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, path);
        registration.setName("mcpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transportProvider,
                                       KbToolService toolService) {
        return McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions("企业知识库问答服务：knowledge_search 检索原始片段，"
                        + "knowledge_answer 基于知识库生成带来源的回答。")
                .tools(toolService.toolSpecifications())
                .build();
    }

    private static String normalizePath(String endpoint) {
        String path = (endpoint == null || endpoint.isBlank()) ? "/mcp" : endpoint.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
