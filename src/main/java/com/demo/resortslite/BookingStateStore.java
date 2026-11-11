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
 * Externalised booking state (cr-java-0065). Replaces in-memory HTTP session storage
 * with Azure Cache for Redis so any application instance can serve any request.
 */
@Component
public class BookingStateStore {

    private static final String LAST_BOOKING_PREFIX = "resortslite:booking:last:";
    private static final String GUEST_PREFIX = "resortslite:booking:guest:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.state.ttl-minutes:30}")
    private long ttlMinutes;

    public void saveBooking(String bookingId, String guestName, Map<String, Object> booking) {
        Duration ttl = Duration.ofMinutes(ttlMinutes);
        try {
            redisTemplate.opsForValue().set(LAST_BOOKING_PREFIX + bookingId,
                    objectMapper.writeValueAsString(booking), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise booking state", e);
        }
        redisTemplate.opsForValue().set(GUEST_PREFIX + bookingId, guestName, ttl);
    }

    public String getGuestName(String bookingId) {
        return redisTemplate.opsForValue().get(GUEST_PREFIX + bookingId);
    }

    public Map<String, Object> getBooking(String bookingId) {
        String json = redisTemplate.opsForValue().get(LAST_BOOKING_PREFIX + bookingId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialise booking state", e);
        }
    }
}
