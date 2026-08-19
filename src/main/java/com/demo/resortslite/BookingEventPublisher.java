package com.demo.resortslite;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * cz-java-0082 FIX: Google Cloud Pub/Sub publisher that decouples tightly-coupled
 * synchronous in-process calls between BookingController and BookingService.
 *
 * Instead of direct method invocations, booking and report events are published
 * asynchronously to dedicated Pub/Sub topics, enabling GKE microservices to operate
 * and scale independently without tight runtime coupling.
 *
 * GCP project and topic names are injected via environment variables — no hardcoded
 * infrastructure values.
 */
@Component
public class BookingEventPublisher {

    private static final Logger LOGGER = Logger.getLogger(BookingEventPublisher.class.getName());

    // cz-java-0082 FIX: GCP project ID injected via environment variable.
    @Value("${GCP_PROJECT_ID:my-gcp-project}")
    private String gcpProjectId;

    // cz-java-0082 FIX: Pub/Sub topic for booking-created events injected via env-var.
    @Value("${PUBSUB_BOOKING_TOPIC:booking-events}")
    private String bookingTopicId;

    // cz-java-0082 FIX: Pub/Sub topic for report-generation events injected via env-var.
    @Value("${PUBSUB_REPORT_TOPIC:report-events}")
    private String reportTopicId;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * cz-java-0082 FIX (BookingController.java Line 84):
     * Publishes a booking-created event to the Pub/Sub booking topic asynchronously.
     * Replaces the synchronous in-process call:
     *   bookingService.createBooking(guestName, roomType, checkIn, checkOut)
     * with an async Pub/Sub message so the controller and service are fully decoupled.
     *
     * @param eventPayload Map containing booking event data to publish
     */
    public void publishBookingEvent(Map<String, Object> eventPayload) {
        publishEvent(bookingTopicId, eventPayload, "booking-created");
    }

    /**
     * cz-java-0082 FIX (BookingService.java Line 102):
     * Publishes a report-generation event to the Pub/Sub report topic asynchronously.
     * Replaces the synchronous in-process return from generateReport(month) with an
     * async Pub/Sub message so the report generation concern is fully decoupled from
     * the booking service.
     *
     * @param eventPayload Map containing report event data to publish
     */
    public void publishReportEvent(Map<String, Object> eventPayload) {
        publishEvent(reportTopicId, eventPayload, "report-requested");
    }

    /**
     * Core Pub/Sub publish helper. Builds a Publisher for the given topic, serialises
     * the payload to JSON, and sends it asynchronously. The Publisher is shut down
     * gracefully after the message is dispatched.
     */
    private void publishEvent(String topicId, Map<String, Object> payload, String eventType) {
        TopicName topicName = TopicName.of(gcpProjectId, topicId);
        Publisher publisher = null;
        try {
            publisher = Publisher.newBuilder(topicName).build();

            String jsonPayload = objectMapper.writeValueAsString(payload);
            ByteString data = ByteString.copyFromUtf8(jsonPayload);

            PubsubMessage pubsubMessage = PubsubMessage.newBuilder()
                    .setData(data)
                    .putAttributes("eventType", eventType)
                    .putAttributes("source", "resortsLite")
                    .build();

            ApiFuture<String> future = publisher.publish(pubsubMessage);

            // Register async callback — no blocking wait; caller is not blocked.
            ApiFutures.addCallback(future, new ApiFutureCallback<String>() {
                @Override
                public void onSuccess(String messageId) {
                    LOGGER.info("Published " + eventType + " event with message ID: " + messageId
                            + " to topic: " + topicId);
                }

                @Override
                public void onFailure(Throwable t) {
                    LOGGER.log(Level.SEVERE, "Failed to publish " + eventType
                            + " event to topic: " + topicId, t);
                }
            }, Executors.newSingleThreadExecutor());

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to build Pub/Sub publisher for topic: " + topicId, e);
        } finally {
            if (publisher != null) {
                try {
                    publisher.shutdown();
                    publisher.awaitTermination(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error shutting down publisher for topic: " + topicId, e);
                }
            }
        }
    }
}
