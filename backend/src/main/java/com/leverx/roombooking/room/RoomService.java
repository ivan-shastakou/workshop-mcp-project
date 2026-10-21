package com.leverx.roombooking.room;

import com.leverx.roombooking.common.ConflictException;
import com.leverx.roombooking.common.NotFoundException;
import com.leverx.roombooking.room.RoomDtos.RoomRequest;
import com.leverx.roombooking.room.RoomDtos.RoomResponse;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class RoomService {

    private final RoomRepository roomRepository;

    public RoomService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    public List<RoomResponse> findAll() {
        return roomRepository.findAll(Sort.by("name")).stream()
                .map(RoomResponse::from)
                .toList();
    }

    public RoomResponse findById(Long id) {
        return RoomResponse.from(getRoom(id));
    }

    @Transactional
    public RoomResponse create(RoomRequest request) {
        if (roomRepository.existsByNameIgnoreCase(request.name())) {
            throw new ConflictException("Room with name '%s' already exists".formatted(request.name()));
        }
        var room = new Room(request.name(), request.location(), request.capacity(), request.description());
        return RoomResponse.from(roomRepository.save(room));
    }

    @Transactional
    public RoomResponse update(Long id, RoomRequest request) {
        var room = getRoom(id);
        if (roomRepository.existsByNameIgnoreCaseAndIdNot(request.name(), id)) {
            throw new ConflictException("Room with name '%s' already exists".formatted(request.name()));
        }
        room.setName(request.name());
        room.setLocation(request.location());
        room.setCapacity(request.capacity());
        room.setDescription(request.description());
        return RoomResponse.from(room);
    }

    @Transactional
    public void delete(Long id) {
        roomRepository.delete(getRoom(id));
    }

    Room getRoom(Long id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Room %d not found".formatted(id)));
    }
}
