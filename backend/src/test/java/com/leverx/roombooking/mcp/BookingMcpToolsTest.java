package com.leverx.roombooking.mcp;

import com.leverx.roombooking.booking.BookingDtos.BookingRequest;
import com.leverx.roombooking.booking.BookingDtos.BookingResponse;
import com.leverx.roombooking.booking.BookingService;
import com.leverx.roombooking.common.ForbiddenException;
import com.leverx.roombooking.room.RoomDtos.RoomResponse;
import com.leverx.roombooking.room.RoomService;
import com.leverx.roombooking.security.CurrentUser;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingMcpToolsTest {

    private static final Instant NINE = Instant.parse("2026-10-20T09:00:00Z");
    private static final Instant TEN = Instant.parse("2026-10-20T10:00:00Z");
    private static final CurrentUser IVAN = new CurrentUser(1L, "Ivan", "ivan@example.com");

    @Mock
    private RoomService roomService;

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingMcpTools tools;

    @Test
    void findsRoomsWithoutOverlappingBookingsAndEnoughSeats() {
        when(roomService.findAll()).thenReturn(List.of(room(1L, "Everest", 10), room(2L, "Orion", 4), room(3L, "Vega", 8)));
        when(bookingService.find(NINE, TEN, null)).thenReturn(List.of(booking(1L, 1L)));

        var free = tools.findFreeRooms("2026-10-20T11:00:00+02:00", "2026-10-20T12:00:00+02:00", 6);

        assertThat(free).extracting(RoomResponse::name).containsExactly("Vega");
    }

    @Test
    void createsBookingForTheUserOfTheMcpRequest() {
        when(bookingService.create(any(), eq(IVAN))).thenReturn(booking(5L, 2L));

        tools.createBooking(contextOf(IVAN), 2L, "Retro", "2026-10-20T09:00:00Z", "2026-10-20T10:00:00Z", null);

        verify(bookingService).create(new BookingRequest(2L, "Retro", null, NINE, TEN), IVAN);
    }

    @Test
    void rejectsCallsWithoutAuthenticatedUser() {
        var anonymous = mock(McpSyncRequestContext.class);
        when(anonymous.transportContext()).thenReturn(McpTransportContext.EMPTY);

        assertThatThrownBy(() -> tools.cancelBooking(anonymous, 5L)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(bookingService);
    }

    private static McpSyncRequestContext contextOf(CurrentUser user) {
        var context = mock(McpSyncRequestContext.class);
        when(context.transportContext()).thenReturn(McpTransportContext.create(Map.of(McpServerConfig.USER, user)));
        return context;
    }

    private static RoomResponse room(Long id, String name, int capacity) {
        return new RoomResponse(id, name, "Floor 1", capacity, null);
    }

    private static BookingResponse booking(Long id, Long roomId) {
        return new BookingResponse(id, roomId, "Room " + roomId, "Sync", IVAN.id(), IVAN.name(), IVAN.email(), null,
                NINE, TEN, NINE, NINE);
    }
}
