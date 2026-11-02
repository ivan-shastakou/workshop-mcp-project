package com.leverx.roombooking.mcp;

import com.leverx.roombooking.booking.BookingDtos.BookingRequest;
import com.leverx.roombooking.booking.BookingDtos.BookingResponse;
import com.leverx.roombooking.booking.BookingService;
import com.leverx.roombooking.room.RoomDtos.RoomResponse;
import com.leverx.roombooking.room.RoomService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import static com.leverx.roombooking.mcp.McpToolUtils.TIME;
import static com.leverx.roombooking.mcp.McpToolUtils.currentUser;
import static com.leverx.roombooking.mcp.McpToolUtils.time;

/**
 * Booking functions exposed as MCP tools. Only a thin adapter: validation, overlap and organizer checks stay in the
 * services, so a booking made by an AI client follows exactly the same rules as one made in the calendar.
 * Exceptions become MCP tool errors with their message, which the model reads and explains to the user.
 */
@Component
public class BookingMcpTools {

    private final RoomService roomService;
    private final BookingService bookingService;

    public BookingMcpTools(RoomService roomService, BookingService bookingService) {
        this.roomService = roomService;
        this.bookingService = bookingService;
    }

    @McpTool(name = "list_rooms", description = "All meeting rooms with id, location and capacity (seats)",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<RoomResponse> listRooms() {
        return roomService.findAll();
    }

    @McpTool(name = "find_free_rooms", description = "Rooms that have no booking overlapping the given time range",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<RoomResponse> findFreeRooms(
            @McpToolParam(description = "Start, " + TIME) String start,
            @McpToolParam(description = "End, " + TIME) String end,
            @McpToolParam(description = "Minimum number of seats", required = false) Integer minCapacity) {
        var busyRoomIds = bookingService.find(time(start, "start"), time(end, "end"), null).stream()
                .map(BookingResponse::roomId)
                .collect(Collectors.toSet());
        return roomService.findAll().stream()
                .filter(room -> !busyRoomIds.contains(room.id()))
                .filter(room -> minCapacity == null || room.capacity() >= minCapacity)
                .toList();
    }

    @McpTool(name = "list_bookings", description = "Bookings overlapping a time range, optionally only for one room",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<BookingResponse> listBookings(
            @McpToolParam(description = "Start, " + TIME) String start,
            @McpToolParam(description = "End, " + TIME) String end,
            @McpToolParam(description = "Room id from list_rooms", required = false) Long roomId) {
        return bookingService.find(time(start, "start"), time(end, "end"), roomId);
    }

    @McpTool(name = "my_bookings", description = "Bookings organized by the current user in a time range",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<BookingResponse> myBookings(
            McpSyncRequestContext context,
            @McpToolParam(description = "Start, " + TIME) String start,
            @McpToolParam(description = "End, " + TIME) String end) {
        var user = currentUser(context);
        return bookingService.find(time(start, "start"), time(end, "end"), null).stream()
                .filter(booking -> user.id().equals(booking.organizerId()))
                .toList();
    }

    @McpTool(name = "create_booking", description = "Book a room; the current user becomes the organizer. "
            + "Fails if the room is already booked in that time range.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public BookingResponse createBooking(
            McpSyncRequestContext context,
            @McpToolParam(description = "Room id from list_rooms") Long roomId,
            @McpToolParam(description = "Meeting title") String title,
            @McpToolParam(description = "Start, " + TIME) String start,
            @McpToolParam(description = "End, " + TIME) String end,
            @McpToolParam(description = "Agenda or notes", required = false) String description) {
        return bookingService.create(
                new BookingRequest(roomId, title, description, time(start, "start"), time(end, "end")),
                currentUser(context));
    }

    @McpTool(name = "update_booking", description = "Change room, title, time or notes of a booking of the current "
            + "user; all fields are set, so pass the current values for what stays the same",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public BookingResponse updateBooking(
            McpSyncRequestContext context,
            @McpToolParam(description = "Booking id") Long bookingId,
            @McpToolParam(description = "Room id from list_rooms") Long roomId,
            @McpToolParam(description = "Meeting title") String title,
            @McpToolParam(description = "Start, " + TIME) String start,
            @McpToolParam(description = "End, " + TIME) String end,
            @McpToolParam(description = "Agenda or notes", required = false) String description) {
        return bookingService.update(bookingId,
                new BookingRequest(roomId, title, description, time(start, "start"), time(end, "end")),
                currentUser(context));
    }

    @McpTool(name = "cancel_booking", description = "Delete a booking of the current user",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
    public String cancelBooking(
            McpSyncRequestContext context,
            @McpToolParam(description = "Booking id") Long bookingId) {
        bookingService.delete(bookingId, currentUser(context));
        return "Booking %d cancelled".formatted(bookingId);
    }
}
