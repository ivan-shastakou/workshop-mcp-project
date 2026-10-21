package com.leverx.roombooking.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("""
            select b from Booking b join fetch b.room
            where b.startTime < :to and b.endTime > :from
            order by b.startTime
            """)
    List<Booking> findInRange(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select b from Booking b join fetch b.room
            where b.room.id = :roomId and b.startTime < :to and b.endTime > :from
            order by b.startTime
            """)
    List<Booking> findInRangeForRoom(@Param("roomId") Long roomId, @Param("from") Instant from, @Param("to") Instant to);
}
