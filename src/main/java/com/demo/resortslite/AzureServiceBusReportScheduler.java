package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

/**
 * Uses Azure Service Bus scheduled messages for distributed, timezone-aware report work.
 */
@Service
public class AzureServiceBusReportScheduler {

    private final ServiceBusSenderClient senderClient;

    public AzureServiceBusReportScheduler(
            @Value("${azure.servicebus.fully-qualified-namespace:}") String namespace,
            @Value("${azure.servicebus.report-queue:}") String queueName) {
        if (StringUtils.hasText(namespace) && StringUtils.hasText(queueName)) {
            this.senderClient = new ServiceBusClientBuilder()
                    .fullyQualifiedNamespace(namespace)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .sender()
                    .queueName(queueName)
                    .buildClient();
        } else {
            this.senderClient = null;
        }
    }

    public String scheduleReport(String reportName, OffsetDateTime scheduledTime) {
        if (senderClient == null) {
            return "Azure Service Bus report scheduling is not configured";
        }
        ServiceBusMessage message = new ServiceBusMessage(reportName);
        long sequenceNumber = senderClient.scheduleMessage(message, scheduledTime);
        return "Scheduled report message " + sequenceNumber + " for " + scheduledTime;
    }
}
