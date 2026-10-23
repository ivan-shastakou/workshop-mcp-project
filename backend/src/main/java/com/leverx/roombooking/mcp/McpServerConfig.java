package com.leverx.roombooking.mcp;

import com.leverx.roombooking.security.CurrentUser;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * The MCP endpoint is protected by the normal security filter chain (JWT from auth-service), like the REST API.
 * Tool handlers may run on another thread than the HTTP request, so instead of the SecurityContext they get the
 * user from the MCP transport context, which is filled here while the request is still available.
 */
@Configuration
class McpServerConfig {

    static final String USER = "user";

    /**
     * Same as Spring AI's auto-configured transport (it backs off when this bean exists), plus the context extractor.
     */
    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(request -> request.principal()
                        .filter(JwtAuthenticationToken.class::isInstance)
                        .map(principal -> CurrentUser.from(((JwtAuthenticationToken) principal).getToken()))
                        .map(user -> McpTransportContext.create(Map.of(USER, user)))
                        .orElse(McpTransportContext.EMPTY))
                .build();
    }
}
