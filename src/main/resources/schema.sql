-- Database schema for ResortsLite application
-- This ensures the bookings table is created on application startup

CREATE TABLE IF NOT EXISTS bookings (
    id VARCHAR(50) PRIMARY KEY,
    guest VARCHAR(255) NOT NULL,
    room VARCHAR(50) NOT NULL,
    checkin VARCHAR(50) NOT NULL,
    checkout VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create index for faster lookups
CREATE INDEX IF NOT EXISTS idx_bookings_guest ON bookings(guest);
CREATE INDEX IF NOT EXISTS idx_bookings_room ON bookings(room);
