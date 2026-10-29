-- PostgreSQL 16 schema initialisation for ResortsLite
-- This script is executed automatically by Spring Boot on startup
-- (spring.sql.init.mode=always, spring.sql.init.platform=postgresql)

-- Bookings table — uses PostgreSQL-native types and snake_case naming convention
CREATE TABLE IF NOT EXISTS bookings (
    id          VARCHAR(20)  PRIMARY KEY,
    guest       VARCHAR(255) NOT NULL,
    room        VARCHAR(50)  NOT NULL,
    checkin     DATE         NOT NULL,
    checkout    DATE         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Index on guest name for lookup performance
CREATE INDEX IF NOT EXISTS idx_bookings_guest ON bookings (guest);
