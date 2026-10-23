package com.leverx.chat.mcp;

import com.leverx.chat.common.ApiProblemException;
import com.leverx.chat.mcp.McpClientRegistry.ConnectionSpec;
import com.leverx.chat.mcp.McpServerDtos.McpServerRequest;
import com.leverx.chat.mcp.McpServerDtos.McpServerResponse;
import com.leverx.chat.mcp.McpServerDtos.McpTestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP servers that a user connects to the chat from the chat UI. Each user sees and uses only their own servers.
 */
@Service
@Transactional(readOnly = true)
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    private final McpServerRepository repository;
    private final McpClientRegistry registry;
    private final McpUrlValidator urlValidator;
    private final McpProperties properties;
    private final UserTokenRelay userTokenRelay;
    private final TextEncryptor encryptor;

    public McpServerService(McpServerRepository repository, McpClientRegistry registry, McpUrlValidator urlValidator,
                            McpProperties properties, UserTokenRelay userTokenRelay) {
        this.repository = repository;
        this.registry = registry;
        this.urlValidator = urlValidator;
        this.properties = properties;
        this.userTokenRelay = userTokenRelay;
        this.encryptor = Encryptors.delux(properties.secrets().password(), properties.secrets().salt());
    }

    public List<McpServerResponse> list(Long userId) {
        return repository.findByOwnerIdOrderByName(userId).stream().map(this::toResponse).toList();
    }

    /**
     * Connects with the given settings without saving them, so the user can check the URL and credentials.
     */
    public McpTestResult test(Long userId, McpServerRequest request) {
        var spec = spec(userId, request.name(), request.url(), request.transport(), request.headerName(),
                request.headerValue(), Boolean.TRUE.equals(request.forwardUserToken()));
        try {
            var probe = registry.probe(spec);
            return new McpTestResult(true, probe.serverInfo(), probe.tools(), null);
        } catch (RuntimeException e) {
            return new McpTestResult(false, null, List.of(), McpClientRegistry.describe(e));
        }
    }

    @Transactional
    public McpServerResponse create(Long userId, McpServerRequest request) {
        var name = request.name().strip();
        if (repository.countByOwnerId(userId) >= properties.maxServersPerUser()) {
            throw ApiProblemException.badRequest(
                    "You can connect at most %d MCP servers".formatted(properties.maxServersPerUser()));
        }
        if (repository.existsByOwnerIdAndNameIgnoreCase(userId, name)) {
            throw ApiProblemException.conflict("You already have an MCP server named '%s'".formatted(name));
        }
        var forwardUserToken = Boolean.TRUE.equals(request.forwardUserToken());
        // validates URL and header before anything is stored
        spec(userId, name, request.url(), request.transport(), request.headerName(), request.headerValue(),
                forwardUserToken);

        var hasHeader = StringUtils.hasText(request.headerName()) && StringUtils.hasText(request.headerValue());
        var server = new McpServer(userId, name, request.url().strip(), request.transport(),
                hasHeader ? request.headerName().strip() : null,
                hasHeader ? encryptor.encrypt(request.headerValue()) : null);
        server.setEnabled(request.enabled() == null || request.enabled());
        server.setForwardUserToken(forwardUserToken);
        server = repository.saveAndFlush(server);
        // without the query string: some servers take an API key there
        log.info("User {} added MCP server {} '{}' ({}, {}{})", userId, server.getId(), name,
                server.getUrl().replaceFirst("\\?.*", ""), server.getTransport(),
                forwardUserToken ? ", user's login token" : "");

        if (server.isEnabled()) {
            registry.toolsOf(server.getId(), spec(server));
        }
        return toResponse(server);
    }

    @Transactional
    public McpServerResponse setEnabled(Long userId, Long id, boolean enabled) {
        var server = getOwn(userId, id);
        server.setEnabled(enabled);
        log.info("User {} {} MCP server {} '{}'", userId, enabled ? "enabled" : "disabled", id, server.getName());
        if (enabled) {
            registry.toolsOf(server.getId(), spec(server));
        } else {
            registry.disconnect(server.getId());
        }
        return toResponse(server);
    }

    public McpServerResponse reconnect(Long userId, Long id) {
        var server = getOwn(userId, id);
        log.info("User {} reconnects MCP server {} '{}'", userId, id, server.getName());
        registry.disconnect(server.getId());
        if (server.isEnabled()) {
            registry.toolsOf(server.getId(), spec(server));
        }
        return toResponse(server);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        var server = getOwn(userId, id);
        registry.disconnect(server.getId());
        repository.delete(server);
        log.info("User {} removed MCP server {} '{}'", userId, id, server.getName());
    }

    /**
     * Tools of all enabled servers of the user, offered to the model with every chat request.
     * Servers that cannot be reached are skipped so the chat keeps working.
     */
    public List<ToolCallback> toolCallbacksFor(Long userId) {
        var tools = new ArrayList<ToolCallback>();
        for (var server : repository.findByOwnerIdAndEnabledTrueOrderByName(userId)) {
            try {
                tools.addAll(registry.toolsOf(server.getId(), spec(server)));
            } catch (ApiProblemException e) {
                // URL no longer passes validation (e.g. allow-list changed); skip the server
                log.warn("Skipping MCP server {}: {}", server.getId(), e.getMessage());
            }
        }
        if (tools.size() > properties.maxToolsPerRequest()) {
            log.warn("User {} has {} MCP tools, only the first {} are offered to the model",
                    userId, tools.size(), properties.maxToolsPerRequest());
            return List.copyOf(tools.subList(0, properties.maxToolsPerRequest()));
        }
        return tools;
    }

    private ConnectionSpec spec(McpServer server) {
        var headerValue = server.getHeaderValueEncrypted() == null ? null : encryptor.decrypt(server.getHeaderValueEncrypted());
        return spec(server.getOwnerId(), server.getName(), server.getUrl(), server.getTransport(), server.getHeaderName(),
                headerValue, server.isForwardUserToken());
    }

    private ConnectionSpec spec(Long userId, String name, String url, McpTransportType transport, String headerName,
                                String headerValue, boolean forwardUserToken) {
        var target = urlValidator.validate(url, transport);
        var hasHeader = StringUtils.hasText(headerName) && StringUtils.hasText(headerValue);
        if (forwardUserToken) {
            if (hasHeader) {
                throw ApiProblemException.badRequest("Use either your login token or an own auth header, not both");
            }
            // checked on every connect, so removing a host from the allow-list stops sending tokens to it
            if (!urlValidator.isAllowListed(url)) {
                throw ApiProblemException.badRequest(
                        "Your login token can only be sent to internal servers listed in MCP_ALLOWED_HOSTS");
            }
            // read on every request: the latest token of this user, so bookings are made in their name
            return new ConnectionSpec(name, target, transport, HttpHeaders.AUTHORIZATION,
                    () -> userTokenRelay.authorization(userId));
        }
        if (hasHeader) {
            urlValidator.validateHeader(headerName.strip());
        }
        var value = hasHeader ? headerValue.strip() : null;
        return new ConnectionSpec(name, target, transport, hasHeader ? headerName.strip() : null, () -> value);
    }

    private McpServer getOwn(Long userId, Long id) {
        return repository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> ApiProblemException.notFound("MCP server %d not found".formatted(id)));
    }

    private McpServerResponse toResponse(McpServer server) {
        var masked = server.getHeaderValueEncrypted() == null ? null : mask(encryptor.decrypt(server.getHeaderValueEncrypted()));
        return new McpServerResponse(server.getId(), server.getName(), server.getUrl(), server.getTransport(),
                server.isEnabled(), server.getHeaderName(), masked, server.isForwardUserToken(),
                registry.status(server.getId(), server.isEnabled()), registry.error(server.getId()),
                registry.serverInfo(server.getId()), registry.tools(server.getId()), server.getCreatedAt());
    }

    static String mask(String secret) {
        return secret.length() <= 10 ? "••••" : secret.substring(0, 4) + "…" + secret.substring(secret.length() - 4);
    }
}
