CREATE TABLE IF NOT EXISTS bookings (
    id       VARCHAR(32) PRIMARY KEY,
    guest    VARCHAR(255),
    room     VARCHAR(64),
    checkin  VARCHAR(32),
    checkout VARCHAR(32)
);
