package com.demo.resortslite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stores booking/guest state in Azure Cache for Redis instead of in-memory HTTP session,
 * keeping application instances stateless.
 */
@Component
public class BookingStateStore {

    private static final String GUEST_KEY_PREFIX = "resortslite:booking:guest:";
    private static final String BOOKING_KEY_PREFIX = "resortslite:booking:data:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.state.ttl-minutes:30}")
    private long ttlMinutes;

    public void saveBookingState(String bookingId, String guestName, Map<String, Object> booking) {
        if (bookingId == null) {
            return;
        }
        redisTemplate.opsForValue().set(GUEST_KEY_PREFIX + bookingId, guestName, ttlMinutes, TimeUnit.MINUTES);
        try {
            redisTemplate.opsForValue().set(BOOKING_KEY_PREFIX + bookingId,
                    objectMapper.writeValueAsString(booking), ttlMinutes, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize booking state", e);
        }
    }

    public String getGuestName(String bookingId) {
        if (bookingId == null) {
            return null;
        }
        return redisTemplate.opsForValue().get(GUEST_KEY_PREFIX + bookingId);
    }
}
