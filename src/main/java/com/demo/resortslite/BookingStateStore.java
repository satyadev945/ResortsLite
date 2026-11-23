package com.demo.resortslite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Externalised booking state (replaces HTTP session storage).
 * State is kept in Azure Cache for Redis so that every application instance sees the
 * same data, keeping the application stateless and horizontally scalable.
 */
@Component
public class BookingStateStore {

    private static final String KEY_PREFIX = "resortslite:booking:";
    private static final String LAST_BOOKING_SUFFIX = ":last";
    private static final String GUEST_SUFFIX = ":guest";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.state.ttl-minutes:30}")
    private long ttlMinutes;

    public void saveBooking(String bookingId, String guestName, Map<String, Object> booking) {
        Duration ttl = Duration.ofMinutes(ttlMinutes);
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + bookingId + LAST_BOOKING_SUFFIX,
                    objectMapper.writeValueAsString(booking), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise booking state", e);
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + bookingId + GUEST_SUFFIX, guestName, ttl);
    }

    public String getGuestName(String bookingId) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + bookingId + GUEST_SUFFIX);
    }

    public Map<String, Object> getBooking(String bookingId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + bookingId + LAST_BOOKING_SUFFIX);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
