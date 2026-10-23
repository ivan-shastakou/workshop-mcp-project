package com.leverx.chat.mcp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the latest JWT of every user who calls chat-service, for MCP servers added with "send my login token"
 * (e.g. the booking backend): they are called with that user's own token, so the user becomes the organizer of
 * bookings made through the chat.
 * The tool calls run on Reactor threads inside the model's tool loop, where the request's SecurityContext is gone;
 * the token is therefore looked up by user id. Tokens are kept in memory only and never logged or stored.
 */
@Component
public class UserTokenRelay implements HandlerInterceptor {

    private final Map<Long, String> tokens = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
            var jwt = authentication.getToken();
            tokens.put(Long.valueOf(jwt.getSubject()), jwt.getTokenValue());
        }
        return true;
    }

    /**
     * Value for the Authorization header, or null if the user has not called chat-service since it started.
     */
    String authorization(Long userId) {
        var token = tokens.get(userId);
        return token == null ? null : "Bearer " + token;
    }
}
