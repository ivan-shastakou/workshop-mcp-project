package com.leverx.roombooking.room;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class RoomDtos {

    private RoomDtos() {
    }

    public record RoomRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) String location,
            @Min(1) int capacity,
            @Size(max = 1000) String description) {
    }

    public record RoomResponse(Long id, String name, String location, int capacity, String description) {

        public static RoomResponse from(Room room) {
            return new RoomResponse(room.getId(), room.getName(), room.getLocation(),
                    room.getCapacity(), room.getDescription());
        }
    }
}
