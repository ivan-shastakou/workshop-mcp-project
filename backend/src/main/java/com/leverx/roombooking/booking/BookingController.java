package com.leverx.roombooking.booking;

import com.leverx.roombooking.booking.BookingDtos.BookingRequest;
import com.leverx.roombooking.booking.BookingDtos.BookingResponse;
import com.leverx.roombooking.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Returns bookings overlapping the [from, to) interval, optionally filtered by room.
     */
    @GetMapping
    public List<BookingResponse> find(@RequestParam Instant from,
                                      @RequestParam Instant to,
                                      @RequestParam(required = false) Long roomId) {
        return bookingService.find(from, to, roomId);
    }

    @GetMapping("/{id}")
    public BookingResponse findById(@PathVariable Long id) {
        return bookingService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@Valid @RequestBody BookingRequest request, @AuthenticationPrincipal Jwt jwt) {
        return bookingService.create(request, CurrentUser.from(jwt));
    }

    @PutMapping("/{id}")
    public BookingResponse update(@PathVariable Long id, @Valid @RequestBody BookingRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        return bookingService.update(id, request, CurrentUser.from(jwt));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        bookingService.delete(id, CurrentUser.from(jwt));
    }
}
