package com.leverx.roombooking.booking;

import com.leverx.roombooking.booking.BookingDtos.BookingRequest;
import com.leverx.roombooking.booking.BookingDtos.BookingResponse;
import com.leverx.roombooking.common.BadRequestException;
import com.leverx.roombooking.common.ConflictException;
import com.leverx.roombooking.common.ForbiddenException;
import com.leverx.roombooking.common.NotFoundException;
import com.leverx.roombooking.room.Room;
import com.leverx.roombooking.room.RoomRepository;
import com.leverx.roombooking.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class BookingService {

    static final Duration MAX_RANGE = Duration.ofDays(366);

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;

    public BookingService(BookingRepository bookingRepository, RoomRepository roomRepository) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
    }

    public List<BookingResponse> find(Instant from, Instant to, Long roomId) {
        validateRange(from, to);
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BadRequestException("Requested range must not exceed %d days".formatted(MAX_RANGE.toDays()));
        }
        var bookings = roomId == null
                ? bookingRepository.findInRange(from, to)
                : bookingRepository.findInRangeForRoom(roomId, from, to);
        return bookings.stream().map(BookingResponse::from).toList();
    }

    public BookingResponse findById(Long id) {
        return BookingResponse.from(getBooking(id));
    }

    @Transactional
    public BookingResponse create(BookingRequest request, CurrentUser user) {
        validateRange(request.startTime(), request.endTime());
        var room = getRoom(request.roomId());
        ensureRoomIsFree(room.getId(), request.startTime(), request.endTime(), null);

        var booking = new Booking(room, request.title().strip(), user,
                request.description(), request.startTime(), request.endTime());
        return BookingResponse.from(bookingRepository.saveAndFlush(booking));
    }

    @Transactional
    public BookingResponse update(Long id, BookingRequest request, CurrentUser user) {
        validateRange(request.startTime(), request.endTime());
        var booking = getOwnBooking(id, user);
        var room = getRoom(request.roomId());
        ensureRoomIsFree(room.getId(), request.startTime(), request.endTime(), id);

        booking.setRoom(room);
        booking.setTitle(request.title().strip());
        booking.setDescription(request.description());
        booking.setStartTime(request.startTime());
        booking.setEndTime(request.endTime());
        return BookingResponse.from(bookingRepository.saveAndFlush(booking));
    }

    @Transactional
    public void delete(Long id, CurrentUser user) {
        bookingRepository.delete(getOwnBooking(id, user));
    }

    private void ensureRoomIsFree(Long roomId, Instant start, Instant end, Long excludedBookingId) {
        bookingRepository.findInRangeForRoom(roomId, start, end).stream()
                .filter(b -> !Objects.equals(b.getId(), excludedBookingId))
                .findFirst()
                .ifPresent(b -> {
                    throw new ConflictException("Room '%s' is already booked from %s to %s (\"%s\")"
                            .formatted(b.getRoom().getName(), b.getStartTime(), b.getEndTime(), b.getTitle()));
                });
    }

    private static void validateRange(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new BadRequestException("Both start and end time are required");
        }
        if (!end.isAfter(start)) {
            throw new BadRequestException("End time must be after start time");
        }
    }

    private Booking getOwnBooking(Long id, CurrentUser user) {
        var booking = getBooking(id);
        if (!booking.isOrganizedBy(user)) {
            throw new ForbiddenException("Only the organizer can change or delete this booking");
        }
        return booking;
    }

    private Booking getBooking(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Booking %d not found".formatted(id)));
    }

    private Room getRoom(Long id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Room %d not found".formatted(id)));
    }
}
