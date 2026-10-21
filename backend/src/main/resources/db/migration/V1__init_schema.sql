CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE room
(
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    location    VARCHAR(200),
    capacity    INTEGER      NOT NULL CHECK (capacity > 0),
    description VARCHAR(1000)
);

CREATE TABLE booking
(
    id          BIGSERIAL PRIMARY KEY,
    room_id     BIGINT       NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    title       VARCHAR(200) NOT NULL,
    organizer   VARCHAR(100) NOT NULL,
    description VARCHAR(2000),
    start_time  TIMESTAMPTZ  NOT NULL,
    end_time    TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT booking_time_range_chk CHECK (end_time > start_time),
    -- guarantees no double booking of a room, even under concurrent requests
    CONSTRAINT booking_no_overlap EXCLUDE USING gist (
        room_id WITH =,
        tstzrange(start_time, end_time, '[)') WITH &&
    )
);

CREATE INDEX booking_time_idx ON booking (start_time, end_time);

INSERT INTO room (name, location, capacity, description)
VALUES ('Everest', '3rd floor, east wing', 12, 'Large meeting room with projector and video conferencing'),
       ('Kilimanjaro', '3rd floor, west wing', 8, 'Medium meeting room with TV screen'),
       ('Elbrus', '2nd floor', 4, 'Small room for quick syncs'),
       ('Mont Blanc', '2nd floor', 2, 'Phone booth for 1:1s and calls'),
       ('Olympus', '1st floor', 30, 'Conference hall for all-hands and workshops');
