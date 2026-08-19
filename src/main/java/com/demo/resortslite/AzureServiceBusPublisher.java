package com.demo.resortslite;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * cz-java-0082: Azure Service Bus publisher component.
 * Decouples tightly-coupled synchronous cross-domain calls between BookingController
 * and BookingService (booking creation) and between BookingService and PAYMENT_API
 * (report/payment processing) by publishing events to Azure Service Bus topics/queues.
 *
 * Each independently deployed AKS microservice subscribes to its own topic/queue,
 * enabling autonomous scaling, independent deployment, and fault isolation.
 *
 * Connection string is sourced from environment variable AZURE_SERVICE_BUS_CONNECTION_STRING,
 * injected via AKS pod spec or Azure Key Vault CSI Driver — never hardcoded.
 */
@Component
public class AzureServiceBusPublisher {

    // cz-java-0082: Azure Service Bus connection string sourced from environment variable.
    // Set AZURE_SERVICE_BUS_CONNECTION_STRING in AKS pod spec or Azure Key Vault CSI Driver.
    @Value("${AZURE_SERVICE_BUS_CONNECTION_STRING:}")
    private String connectionString;

    // cz-java-0082: Queue name for booking creation events. Configurable via env var.
    @Value("${AZURE_SERVICE_BUS_BOOKING_QUEUE:booking-events-queue}")
    private String bookingQueueName;

    // cz-java-0082: Queue name for report/payment processing events. Configurable via env var.
    @Value("${AZURE_SERVICE_BUS_REPORT_QUEUE:report-events-queue}")
    private String reportQueueName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * cz-java-0082: Publishes a booking creation event to the Azure Service Bus booking queue.
     * Replaces the tightly-coupled synchronous call bookingService.createBooking(...) in
     * BookingController, decoupling the controller from the booking domain service.
     * The downstream booking processor microservice on AKS consumes this event independently.
     *
     * @param bookingPayload Map containing booking details (guestName, roomType, checkIn, checkOut)
     * @param correlationId  Unique correlation ID for distributed tracing across AKS services
     */
    public void publishBookingCreatedEvent(Map<String, Object> bookingPayload, String correlationId) {
        if (connectionString == null || connectionString.isBlank()) {
            // Fallback: log warning when Service Bus is not configured (local dev mode)
            System.out.println("[AzureServiceBusPublisher] WARNING: AZURE_SERVICE_BUS_CONNECTION_STRING "
                    + "is not set. Booking event not published. CorrelationId=" + correlationId);
            return;
        }
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(bookingQueueName)
                .buildClient()) {

            String messageBody = objectMapper.writeValueAsString(bookingPayload);
            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            message.setCorrelationId(correlationId);
            message.setContentType("application/json");
            // cz-java-0082: Message subject identifies the event type for downstream consumers.
            message.setSubject("BookingCreated");

            sender.sendMessage(message);
            System.out.println("[AzureServiceBusPublisher] BookingCreated event published to queue '"
                    + bookingQueueName + "'. CorrelationId=" + correlationId);
        } catch (Exception e) {
            System.err.println("[AzureServiceBusPublisher] Failed to publish BookingCreated event: "
                    + e.getMessage());
        }
    }

    /**
     * cz-java-0082: Publishes a report generation event to the Azure Service Bus report queue.
     * Replaces the tightly-coupled synchronous reference to PAYMENT_API in
     * BookingService.generateReport(), decoupling the booking domain from the payment/reporting
     * domain. The downstream report processor microservice on AKS consumes this event independently.
     *
     * @param reportPayload Map containing report details (month, reportType, requestedBy)
     * @param correlationId Unique correlation ID for distributed tracing across AKS services
     */
    public void publishReportGenerationEvent(Map<String, Object> reportPayload, String correlationId) {
        if (connectionString == null || connectionString.isBlank()) {
            // Fallback: log warning when Service Bus is not configured (local dev mode)
            System.out.println("[AzureServiceBusPublisher] WARNING: AZURE_SERVICE_BUS_CONNECTION_STRING "
                    + "is not set. Report event not published. CorrelationId=" + correlationId);
            return;
        }
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            String messageBody = objectMapper.writeValueAsString(reportPayload);
            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            message.setCorrelationId(correlationId);
            message.setContentType("application/json");
            // cz-java-0082: Message subject identifies the event type for downstream consumers.
            message.setSubject("ReportGenerationRequested");

            sender.sendMessage(message);
            System.out.println("[AzureServiceBusPublisher] ReportGenerationRequested event published to queue '"
                    + reportQueueName + "'. CorrelationId=" + correlationId);
        } catch (Exception e) {
            System.err.println("[AzureServiceBusPublisher] Failed to publish ReportGenerationRequested event: "
                    + e.getMessage());
        }
    }
}
