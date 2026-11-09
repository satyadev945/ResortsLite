# ResortsLite — Modernised Java 25 / Spring Boot 3.5 Demo Application

A compact Spring Boot 3.5.x resort booking application upgraded from the legacy
Java 8 / Spring Boot 2.7.x baseline.

**Purpose:** Hands-on Concierto Modernize demo — scan, assess, and transform.

---

## Tech Stack

| Item | Version |
|---|---|
| Java | 25 (with --enable-preview) |
| Spring Boot | 3.5.0 |
| Spring MVC | 6.x |
| Build | Maven |
| Database | H2 in-memory (dev) / Oracle 23c (prod) |
| Jakarta EE | 10 (jakarta.* namespace) |

---

## Key Transformations Applied

| Area | Change |
|---|---|
| Java version | 1.8 → 25 |
| Spring Boot | 2.7.18 → 3.5.0 |
| Namespace | `javax.*` → `jakarta.*` |
| Log4j | 2.14.1 (CVE-2021-44228) → 2.23.1 (safe) |
| Commons Collections | 3.2.1 (CVE-2015-6420) → 4.4 (safe) |
| Hashing | MD5 (broken) → SHA-256 |
| Date/Time API | `SimpleDateFormat` (not thread-safe) → `DateTimeFormatter` |
| Charset | Platform-default → explicit UTF-8 |
| JPA Dialect | `Oracle12cDialect` (removed in Hibernate 6) → `OracleDialect` |
| JDBC Driver | ojdbc8 → ojdbc11 (Java 25 module-system compatible) |

---

## Violation Traceability Matrix

| Rule ID | Domain | Severity | File | Line(s) | Description | Status |
|---|---|---|---|---|---|---|
| cr-java-0065 | Cloud Compatibility | Mandatory | BookingController.java | 33, 34, 48 | HTTP session state storage — breaks auto-scaling | Open |
| cr-java-0067 | Cloud Compatibility | Potential | BookingController.java | 18 | In-memory cache without TTL — instance-local | Open |
| cr-java-0088 | Cloud Compatibility | Mandatory | BookingController.java | 60 | Plain HTTP URL for internal service call | Open |
| cr-java-0088 | Cloud Compatibility | Mandatory | ReportService.java | 59 | Plain HTTP URL for report download | Open |
| cr-java-0021 | Cloud Compatibility | Mandatory | BookingService.java | 20, 25 | Hardcoded DB hostname + payment API endpoint | Open |
| cr-java-0021 | Cloud Compatibility | Mandatory | application.properties | 12–17 | Hardcoded internal service endpoints | Open |
| czr-java-001 | Software Portability | Mandatory | BookingController.java | 71 | Hardcoded absolute file path in controller | Open |
| czr-java-001 | Software Portability | Mandatory | ReportService.java | 17, 20 | Hardcoded Linux + Windows absolute paths | Open |
| czr-port-001 | Software Portability | High | ReportService.java | 24 | Fixed server port — blocks ECS/EKS dynamic binding | Open |
| sql-inject-001 | Security Health | Critical | BookingService.java | 36–38 | SQL injection via string concatenation (INSERT) | Open |
| sql-inject-001 | Security Health | Critical | BookingService.java | 53 | SQL injection via string concatenation (SELECT) | Open |
| sec-cred-001 | Security Health | Critical | BookingService.java | 21, 22 | Hardcoded database credentials in source code | Open |
| sec-weak-hash-001 | Security Health | High | BookingService.java | 43, 91, 92 | MD5 → **Fixed: SHA-256** | **Fixed** |
| CVE-2021-44228 | Security Health | Critical | pom.xml | 35 | Log4j 2.14.1 → **Fixed: 2.23.1** | **Fixed** |
| CVE-2015-6420 | Security Health | High | pom.xml | 41 | commons-collections 3.2.1 → **Fixed: 4.4** | **Fixed** |
| dup-logic-001 | Code Sustainability | Medium | BookingService.java | 71–72 | Duplicated room type validation | Open |
| complexity-001 | Code Sustainability | High | BookingService.java | 65–82 | Cyclomatic complexity > 9 in calculateRoomPrice | Open |
| doc-missing-001 | Code Sustainability | Medium | ReportService.java | 55, 63 | Missing JavaDoc on public methods | Open |

---

## Build Status

| Metric | Value |
|---|---|
| Compilation Errors | **0** |
| Java Version | 25 |
| Spring Boot | 3.5.0 |

---

## How to Run

```bash
mvn spring-boot:run
```

App starts on http://localhost:8080

**H2 Console:** http://localhost:8080/h2-console

**Sample Endpoints:**
```
POST /api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05
GET  /api/bookings/status/{bookingId}
GET  /api/bookings/availability?roomType=DELUXE
GET  /api/bookings/report/download?month=june
```

---

## Line Count Summary

| File | Lines |
|---|---|
| pom.xml | 110 |
| ResortsLiteApplication.java | 11 |
| BookingController.java | 82 |
| BookingService.java | 100 |
| ReportService.java | 69 |
| entity/Doctor.java | 60 |
| application.properties | 22 |
| **Total** | **454** |

*Java source lines only: 322*
