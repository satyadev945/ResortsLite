package com.demo.resortslite;

import net.spy.memcached.MemcachedClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// cz-java-0063: Removed javax.servlet.http.HttpSession import — replaced with stateless JWT.
// JWT signing secret is injected from AWS Secrets Manager via ECS Fargate task environment variable.
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Logger logger = Logger.getLogger(BookingController.class.getName());

    @Autowired
    private BookingService bookingService;

    // cz-java-0063: JwtUtil handles stateless token generation/parsing (replaces HttpSession).
    @Autowired
    private JwtUtil jwtUtil;

    // EFS-backed mount path resolved via environment variable for container portability (cz-java-0057)
    @Value("${app.report.base-path:/mnt/efs/reports}")
    private String reportBasePath;

    // cz-java-0082: Inventory service base URL resolved via ECS Service Connect environment variable.
    // ECS Service Connect provides automatic service discovery, mTLS, and traffic observability
    // between independently deployed Fargate services. Configure INVENTORY_SERVICE_URL in the
    // ECS task definition to the Service Connect endpoint (e.g. http://inventory-service:8081).
    @Value("${INVENTORY_SERVICE_URL:http://inventory-service:8081}")
    private String inventoryServiceUrl;

    // cz-java-0070 [Local Caches — Distributed Cache Fix]:
    // The previous instance-local HashMap bookingCache did not work effectively when containers
    // scale horizontally in ECS Fargate — each task held its own isolated cache, causing
    // cache-miss inconsistencies across instances.
    //
    // REMEDIATION — Amazon ElastiCache for Memcached via AWS SSM Parameter Store:
    //   The Memcached endpoint is injected at runtime from AWS SSM Parameter Store through the
    //   ECS Fargate task definition environment variable MEMCACHED_ENDPOINT. All Fargate tasks
    //   share the same ElastiCache Memcached cluster, ensuring consistent distributed caching
    //   regardless of horizontal scale-out or task replacement events.
    //
    //   Required AWS / ECS configuration:
    //     1. Provision an Amazon ElastiCache cluster (Memcached engine) in the same VPC.
    //     2. Store the cluster endpoint in AWS SSM Parameter Store:
    //          aws ssm put-parameter \
    //            --name "/resortslite/memcached/endpoint" \
    //            --value "<cluster-endpoint>:11211" \
    //            --type String
    //     3. In the ECS Fargate task definition, add a valueFrom secret/parameter reference:
    //          { "name": "MEMCACHED_ENDPOINT",
    //            "valueFrom": "arn:aws:ssm:<region>:<account>:parameter/resortslite/memcached/endpoint" }
    //     4. Ensure the ECS task execution role has ssm:GetParameters permission for the path.
    //     5. Security group for the ElastiCache cluster must allow inbound TCP 11211 from the
    //        ECS task security group.
    //
    // cz-java-0070: MEMCACHED_ENDPOINT is resolved from AWS SSM Parameter Store at task startup.
    @Value("${MEMCACHED_ENDPOINT:localhost:11211}")
    private String memcachedEndpoint;

    // cz-java-0070: Lazily-initialised Memcached client backed by Amazon ElastiCache.
    // Shared across all requests within a single task; the cluster is shared across all tasks.
    private MemcachedClient memcachedClient;

    /**
     * cz-java-0070: Returns a lazily-initialised MemcachedClient connected to the
     * Amazon ElastiCache endpoint injected via MEMCACHED_ENDPOINT (AWS SSM Parameter Store).
     * Falls back gracefully if the endpoint is unavailable, logging the error.
     */
    private MemcachedClient getMemcachedClient() {
        if (memcachedClient == null) {
            try {
                String host = memcachedEndpoint.contains(":")
                        ? memcachedEndpoint.substring(0, memcachedEndpoint.lastIndexOf(':'))
                        : memcachedEndpoint;
                int port = memcachedEndpoint.contains(":")
                        ? Integer.parseInt(memcachedEndpoint.substring(memcachedEndpoint.lastIndexOf(':') + 1))
                        : 11211;
                memcachedClient = new MemcachedClient(new InetSocketAddress(host, port));
            } catch (IOException e) {
                logger.warning("cz-java-0070: Could not connect to Memcached at "
                        + memcachedEndpoint + ": " + e.getMessage());
            }
        }
        return memcachedClient;
    }

    // cz-java-0070: Cache TTL in seconds (1 hour). Tune via CACHE_TTL_SECONDS env var if needed.
    private static final int CACHE_TTL_SECONDS = 3600;

    /**
     * cz-java-0063: HttpSession parameter removed. Booking state is no longer stored in
     * server-side session memory. A stateless JWT token embedding guestName and bookingId
     * is returned in the response so any ECS Fargate instance can validate it independently.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0063: Build a stateless JWT token containing booking context.
        // The signing secret (JWT_SECRET) is injected from AWS Secrets Manager via
        // ECS Fargate task definition — no server-side session state is stored.
        Map<String, Object> tokenClaims = new HashMap<>();
        tokenClaims.put("guestName", guestName);
        tokenClaims.put("bookingId", booking.get("bookingId"));
        String jwtToken = jwtUtil.generateToken(tokenClaims, guestName);

        // cz-java-0070: Write booking to Amazon ElastiCache (Memcached) instead of the
        // former instance-local HashMap. All ECS Fargate tasks share this distributed cache,
        // so any task can serve subsequent reads without a cache miss after scale-out.
        String bookingId = (String) booking.get("bookingId");
        MemcachedClient mc = getMemcachedClient();
        if (mc != null) {
            mc.set(bookingId, CACHE_TTL_SECONDS, booking.toString());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // cz-java-0063: Return JWT token to client; client must include it as
        // "Authorization: Bearer <token>" on subsequent requests.
        response.put("token", jwtToken);
        return response;
    }

    /**
     * cz-java-0063: HttpSession parameter removed. Guest identity is now resolved from the
     * stateless JWT Bearer token in the Authorization header — works across all ECS Fargate
     * instances without sticky sessions or shared session storage.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        // cz-java-0063: Extract guestName from the stateless JWT token instead of HttpSession.
        // Any ECS Fargate instance can validate the token using the shared JWT_SECRET.
        String lastGuest = jwtUtil.extractClaim(authorizationHeader, "guestName");

        // cz-java-0070: Attempt to read booking from Amazon ElastiCache (Memcached) first.
        // Falls back to the database via bookingService if the cache entry has expired or
        // the Memcached client is unavailable.
        Object cachedBooking = null;
        MemcachedClient mc = getMemcachedClient();
        if (mc != null) {
            cachedBooking = mc.get(bookingId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        result.put("cacheHit", cachedBooking != null);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cz-java-0082: Replaced hardcoded inter-service URL with ECS Service Connect
        // environment variable (INVENTORY_SERVICE_URL). ECS Service Connect provides
        // automatic service discovery, mTLS, and traffic observability between independently
        // deployed Fargate services. The URL is injected at runtime via the ECS task
        // definition — no hardcoded hostnames or IP addresses remain in the source code.
        String inventoryUrl = inventoryServiceUrl + "/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cz-java-0057: Replaced hardcoded absolute path with EFS-backed environment variable.
        // Mount the EFS volume at the path specified by APP_REPORT_BASE_PATH in ECS task definition.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
