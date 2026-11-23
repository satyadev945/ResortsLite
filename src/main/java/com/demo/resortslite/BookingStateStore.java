package com.demo.resortslite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Externalised booking state backed by Google Cloud Memorystore for Redis.
 * Replaces HTTP session storage so any application instance can serve any request.
 */
@Component
public class BookingStateStore {

    private static final String BOOKING_KEY_PREFIX = "resorts:booking:";
    private static final String GUEST_KEY_PREFIX = "resorts:booking-guest:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.state.ttl-minutes:30}")
    private long ttlMinutes;

    public void saveBooking(String bookingId, String guestName, Map<String, Object> booking) {
        Duration ttl = Duration.ofMinutes(ttlMinutes);
        try {
            redisTemplate.opsForValue().set(BOOKING_KEY_PREFIX + bookingId,
                    objectMapper.writeValueAsString(booking), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to store booking state in Redis", e);
        }
        redisTemplate.opsForValue().set(GUEST_KEY_PREFIX + bookingId, guestName, ttl);
    }

    public String getGuestName(String bookingId) {
        return redisTemplate.opsForValue().get(GUEST_KEY_PREFIX + bookingId);
    }

    public Map<String, Object> getBooking(String bookingId) {
        String json = redisTemplate.opsForValue().get(BOOKING_KEY_PREFIX + bookingId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }
}
