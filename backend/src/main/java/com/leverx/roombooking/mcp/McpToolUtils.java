package com.leverx.roombooking.mcp;

import com.leverx.roombooking.common.BadRequestException;
import com.leverx.roombooking.common.ForbiddenException;
import com.leverx.roombooking.security.CurrentUser;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

public final class McpToolUtils {

    public static final String TIME = "ISO-8601 date-time with offset, e.g. 2026-10-23T10:00:00+02:00";

    private McpToolUtils() {
    }

    public static CurrentUser currentUser(McpSyncRequestContext context) {
        if (context.transportContext().get(McpServerConfig.USER) instanceof CurrentUser user) {
            return user;
        }
        throw new ForbiddenException("Not authenticated");
    }

    /**
     * Models sometimes send times without an offset; a clear message lets them retry with the right format.
     */
    public static Instant time(String value, String field) {
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequestException("%s must be an %s, got '%s'".formatted(field, TIME, value));
        }
    }
}
