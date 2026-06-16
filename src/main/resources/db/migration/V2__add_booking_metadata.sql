-- Add metadata columns to bookings table for tracking ownership and permissions
-- Migration V2: Add booking metadata fields

-- Add created_by column to track which user created the booking
ALTER TABLE bookings ADD COLUMN created_by VARCHAR(255);

-- Add created_at column to track when the booking was created
ALTER TABLE bookings ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Add access_level column to track permission level (USER, ADMIN, etc.)
ALTER TABLE bookings ADD COLUMN access_level VARCHAR(50) DEFAULT 'USER';

-- Create index on created_by for query performance
CREATE INDEX idx_bookings_created_by ON bookings(created_by);

-- Update existing rows to have default values
UPDATE bookings SET access_level = 'USER' WHERE access_level IS NULL;
UPDATE bookings SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL;
