package com.leverx.roombooking.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record BookingRequest(
            @NotNull Long roomId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull Instant startTime,
            @NotNull Instant endTime) {
    }

    public record BookingResponse(
            Long id,
            Long roomId,
            String roomName,
            String title,
            Long organizerId,
            String organizerName,
            String organizerEmail,
            String description,
            Instant startTime,
            Instant endTime,
            Instant createdAt,
            Instant updatedAt) {

        public static BookingResponse from(Booking booking) {
            return new BookingResponse(
                    booking.getId(),
                    booking.getRoom().getId(),
                    booking.getRoom().getName(),
                    booking.getTitle(),
                    booking.getOrganizerId(),
                    booking.getOrganizerName(),
                    booking.getOrganizerEmail(),
                    booking.getDescription(),
                    booking.getStartTime(),
                    booking.getEndTime(),
                    booking.getCreatedAt(),
                    booking.getUpdatedAt());
        }
    }
}
