package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PostConstruct;

@SpringBootApplication
public class ResortsLiteApplication {

    private final JdbcTemplate jdbcTemplate;

    public ResortsLiteApplication(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }

    @PostConstruct
    void initializeSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS bookings (
                    id VARCHAR(64) PRIMARY KEY,
                    guest VARCHAR(255) NOT NULL,
                    room VARCHAR(64) NOT NULL,
                    checkin VARCHAR(32) NOT NULL,
                    checkout VARCHAR(32) NOT NULL
                )
                """);
    }
}
