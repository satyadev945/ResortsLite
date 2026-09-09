# ResortsLite — Modernized Java 21 Demo Application

A compact Spring Boot 3.2.x resort booking application modernized from Java 8 / Spring Boot 2.7.x.

## Tech Stack

| Item | Version |
|---|---|
| Java | 21 |
| Spring Boot | 3.2.2 |
| Spring MVC | 6.1.x |
| Build | Maven |
| Database | H2 in-memory |

## Modernization Summary

### Java 8 → 21 Upgrade
- Updated Spring Boot from 2.7.18 to 3.2.2 (Jakarta EE 10)
- Updated Java version from 1.8 to 21
- Updated Maven compiler plugin to 3.11.0 with `release` flag
- Replaced MD5 hashing with SHA-256
- Replaced `SimpleDateFormat`/`Date` with `java.time.LocalDateTime`
- Used switch expressions (Java 14+ feature)
- Used `StandardCharsets.UTF_8` instead of platform default

### Security Fixes
- Updated log4j-core from 2.14.1 to 2.23.1 (fixes CVE-2021-44228 Log4Shell)
- Replaced commons-collections 3.2.1 with commons-collections4 4.4 (fixes CVE-2015-6420)
- Fixed SQL injection by using parameterized queries
- Removed hardcoded database credentials (externalized via environment variables)
- Replaced MD5 with SHA-256 for confirmation codes

### Cloud Compatibility
- Removed HTTP session state storage (stateless design)
- Removed in-memory cache (instance-local, breaks horizontal scaling)
- Replaced plain HTTP URLs with HTTPS
- Externalized all infrastructure endpoints via environment variables
- Removed hardcoded file paths (using configurable paths)
- Dynamic server port assignment via `PORT` environment variable

### Code Sustainability
- Refactored high-complexity `calculateRoomPrice` into smaller methods
- Eliminated duplicated room type validation logic
- Added JavaDoc to all public methods in ReportService

## How to Run

```bash
mvn spring-boot:run
```

App starts on http://localhost:${PORT:8080}

**H2 Console:** http://localhost:${PORT:8080}/h2-console

**Sample Endpoints:**
```
POST /api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05
GET  /api/bookings/status/{bookingId}
GET  /api/bookings/availability?roomType=DELUXE
GET  /api/bookings/report/download?month=june
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | 8080 | Server port |
| `DB_URL` | jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1 | Database URL |
| `DB_USERNAME` | sa | Database username |
| `DB_PASSWORD` | (empty) | Database password |
| `PAYMENT_ENDPOINT` | https://payment-svc.internal:8443/charge | Payment service URL |
| `INVENTORY_ENDPOINT` | https://inventory-svc.internal:8443/rooms | Inventory service URL |
| `NOTIFICATION_ENDPOINT` | https://notify.internal:8443/send | Notification service URL |
| `REPORT_BASE_PATH` | ./reports | Report base directory |
| `REPORT_BACKUP_PATH` | ./backups | Report backup directory |
