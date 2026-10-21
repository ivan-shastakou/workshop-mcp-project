-- Organizer is now the authenticated user who created the booking (user lives in auth-service DB).
-- organizer_id stays nullable only for bookings created before authentication was introduced.
ALTER TABLE booking RENAME COLUMN organizer TO organizer_name;
ALTER TABLE booking ADD COLUMN organizer_id BIGINT;
ALTER TABLE booking ADD COLUMN organizer_email VARCHAR(255);

CREATE INDEX booking_organizer_idx ON booking (organizer_id);
