package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Externalised booking state backed by Google Cloud Memorystore for Redis.
 * Replaces HTTP session storage so that any application instance can serve any request.
 */
@Component
public class BookingStateStore {

    private static final String KEY_PREFIX = "resorts:booking:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public BookingStateStore(StringRedisTemplate redisTemplate,
                             @Value("${app.booking.state-ttl-seconds:1800}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public void saveBooking(String bookingId, String guestName, Map<String, Object> booking) {
        Map<String, String> fields = new HashMap<>();
        for (Map.Entry<String, Object> e : booking.entrySet()) {
            if (e.getValue() != null) {
                fields.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        if (guestName != null) {
            fields.put("guestName", guestName);
        }
        String key = KEY_PREFIX + bookingId;
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, ttl);
    }

    public String getGuestName(String bookingId) {
        Object value = redisTemplate.opsForHash().get(KEY_PREFIX + bookingId, "guestName");
        return value == null ? null : value.toString();
    }
}
