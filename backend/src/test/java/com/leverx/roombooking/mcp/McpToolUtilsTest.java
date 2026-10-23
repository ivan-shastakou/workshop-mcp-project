package com.leverx.roombooking.mcp;

import com.leverx.roombooking.common.BadRequestException;
import com.leverx.roombooking.common.ForbiddenException;
import com.leverx.roombooking.security.CurrentUser;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolUtilsTest {

    @Test
    void returnsTheUserFromTheMcpTransportContext() {
        var user = new CurrentUser(1L, "Ivan", "ivan@example.com");
        var context = context(McpTransportContext.create(Map.of(McpServerConfig.USER, user)));

        assertThat(McpToolUtils.currentUser(context)).isSameAs(user);
    }

    @Test
    void rejectsMissingOrInvalidAuthenticatedUser() {
        assertThatThrownBy(() -> McpToolUtils.currentUser(context(McpTransportContext.EMPTY)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Not authenticated");
        assertThatThrownBy(() -> McpToolUtils.currentUser(
                context(McpTransportContext.create(Map.of(McpServerConfig.USER, "invalid")))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Not authenticated");
    }

    @Test
    void convertsOffsetTimesToInstantsAndTrimsWhitespace() {
        assertThat(McpToolUtils.time(" 2026-10-20T11:00:00+02:00 ", "start"))
                .isEqualTo(Instant.parse("2026-10-20T09:00:00Z"));
        assertThat(McpToolUtils.time("2026-10-20T10:00:00Z", "end"))
                .isEqualTo(Instant.parse("2026-10-20T10:00:00Z"));
    }

    @Test
    void explainsTheExpectedTimeFormat() {
        assertThatThrownBy(() -> McpToolUtils.time("2026-10-20T10:00", "start"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("start must be an %s, got '2026-10-20T10:00'", McpToolUtils.TIME);
        assertThatThrownBy(() -> McpToolUtils.time(null, "end"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("end must be an %s, got 'null'", McpToolUtils.TIME);
    }

    private static McpSyncRequestContext context(McpTransportContext transportContext) {
        var context = mock(McpSyncRequestContext.class);
        when(context.transportContext()).thenReturn(transportContext);
        return context;
    }
}
