CREATE TABLE IF NOT EXISTS bookings (
    id VARCHAR(50) PRIMARY KEY,
    guest VARCHAR(255),
    room VARCHAR(100),
    checkin VARCHAR(50),
    checkout VARCHAR(50)
);
