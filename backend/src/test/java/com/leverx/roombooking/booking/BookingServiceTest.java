package com.leverx.roombooking.booking;

import com.leverx.roombooking.booking.BookingDtos.BookingRequest;
import com.leverx.roombooking.common.BadRequestException;
import com.leverx.roombooking.common.ConflictException;
import com.leverx.roombooking.common.ForbiddenException;
import com.leverx.roombooking.room.Room;
import com.leverx.roombooking.room.RoomRepository;
import com.leverx.roombooking.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final Instant NINE = Instant.parse("2026-10-20T09:00:00Z");
    private static final Instant TEN = Instant.parse("2026-10-20T10:00:00Z");
    private static final CurrentUser IVAN = new CurrentUser(1L, "Ivan", "ivan@example.com");
    private static final CurrentUser ANNA = new CurrentUser(2L, "Anna", "anna@example.com");

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private RoomRepository roomRepository;

    @InjectMocks
    private BookingService bookingService;

    @Test
    void createsBookingWhenRoomIsFree() {
        var room = room(1L, "Everest");
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(bookingRepository.findInRangeForRoom(1L, NINE, TEN)).thenReturn(List.of());
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = bookingService.create(request(1L, NINE, TEN), IVAN);

        assertThat(response.roomName()).isEqualTo("Everest");
        assertThat(response.title()).isEqualTo("Sync");
        assertThat(response.organizerId()).isEqualTo(IVAN.id());
        assertThat(response.organizerName()).isEqualTo("Ivan");
    }

    @Test
    void rejectsOverlappingBooking() {
        var room = room(1L, "Everest");
        var existing = booking(7L, room, ANNA);
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(bookingRepository.findInRangeForRoom(1L, NINE, TEN)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> bookingService.create(request(1L, NINE, TEN), IVAN))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Everest");
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateIgnoresOverlapWithItself() {
        var room = room(1L, "Everest");
        var existing = booking(7L, room, IVAN);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(bookingRepository.findInRangeForRoom(1L, NINE, TEN)).thenReturn(List.of(existing));
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = bookingService.update(7L, request(1L, NINE, TEN), IVAN);

        assertThat(response.id()).isEqualTo(7L);
    }

    @Test
    void onlyOrganizerCanUpdateBooking() {
        var existing = booking(7L, room(1L, "Everest"), ANNA);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> bookingService.update(7L, request(1L, NINE, TEN), IVAN))
                .isInstanceOf(ForbiddenException.class);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void onlyOrganizerCanDeleteBooking() {
        var existing = booking(7L, room(1L, "Everest"), ANNA);
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> bookingService.delete(7L, IVAN))
                .isInstanceOf(ForbiddenException.class);
        verify(bookingRepository, never()).delete(any());
    }

    @Test
    void rejectsEndBeforeStart() {
        assertThatThrownBy(() -> bookingService.create(request(1L, TEN, NINE), IVAN))
                .isInstanceOf(BadRequestException.class);
    }

    private static BookingRequest request(Long roomId, Instant start, Instant end) {
        return new BookingRequest(roomId, "Sync", null, start, end);
    }

    private static Room room(Long id, String name) {
        var room = new Room(name, null, 10, null);
        ReflectionTestUtils.setField(room, "id", id);
        return room;
    }

    private static Booking booking(Long id, Room room, CurrentUser organizer) {
        var booking = new Booking(room, "Existing", organizer, null, NINE, TEN);
        ReflectionTestUtils.setField(booking, "id", id);
        return booking;
    }
}
