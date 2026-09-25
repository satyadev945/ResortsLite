# ResortsLite — Modernized Java 21 / Spring Boot 3.2.x Demo Application

A compact Spring Boot resort booking application that has been **modernized** from
legacy Java 8 patterns to cloud-native, secure, and portable code.

---

## Tech Stack

| Item | Version |
|---|---|
| Java | 21 |
| Spring Boot | 3.2.x |
| Spring MVC | 6.x |
| Build | Maven |
| Database | Oracle (H2 for dev) |

---

## Transformation Summary

All COMPASS violations have been remediated:

| Rule ID | Domain | Severity | Fix Applied |
|---|---|---|---|
| cr-java-0065 | Cloud Compatibility | Mandatory | Removed HTTP session state storage — fully stateless |
| cr-java-0067 | Cloud Compatibility | Potential | Removed in-memory cache — use Redis for distributed caching |
| cr-java-0088 | Cloud Compatibility | Mandatory | All internal service URLs use HTTPS |
| cr-java-0021 | Cloud Compatibility | Mandatory | All hostnames/endpoints externalised to environment variables |
| czr-java-001 | Software Portability | Mandatory | All file paths externalised via configuration |
| czr-port-001 | Software Portability | High | Removed fixed server port — dynamic port binding |
| sql-inject-001 | Security Health | Critical | All SQL queries use parameterized statements |
| sec-cred-001 | Security Health | Critical | Database credentials externalised to environment variables |
| sec-weak-hash-001 | Security Health | High | MD5 replaced with SHA-256 |
| CVE-2021-44228 | Security Health | Critical | Log4j updated to 2.23.1 |
| CVE-2015-6420 | Security Health | High | commons-collections updated to 4.4 |
| dup-logic-001 | Code Sustainability | Medium | Room type validation extracted to shared enum |
| complexity-001 | Code Sustainability | High | calculateRoomPrice refactored with enums to reduce complexity |
| doc-missing-001 | Code Sustainability | Medium | JavaDoc added to all public methods |

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

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | Database JDBC URL | `jdbc:oracle:thin:@localhost:1521:ORCL` |
| `DB_USER` | Database username | `app` |
| `DB_PASS` | Database password | (empty) |
| `DB_HOST` | Database hostname | `localhost` |
| `PAYMENT_ENDPOINT` | Payment service URL | `https://payment-svc.internal/charge` |
| `INVENTORY_ENDPOINT` | Inventory service URL | `https://inventory-svc.internal/rooms` |
| `NOTIFICATION_ENDPOINT` | Notification service URL | `https://notify.internal/send` |
| `REPORT_BASE_PATH` | Report file base path | `/tmp/reports` |
| `BACKUP_BASE_PATH` | Backup file base path | `/tmp/backups` |
| `PORT` | Server port | `8080` |
