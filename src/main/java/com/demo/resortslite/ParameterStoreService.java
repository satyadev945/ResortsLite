package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import javax.annotation.PreDestroy;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves environment-specific configuration (service URLs/endpoints) from AWS Systems Manager
 * Parameter Store. AWS credentials come from the default provider chain (IAM role).
 * If a parameter cannot be read, the supplied fallback (from external configuration) is used.
 */
@Service
public class ParameterStoreService {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Value("${app.ssm.enabled:true}")
    private boolean enabled;

    @Value("${app.aws.region:${AWS_REGION:us-east-1}}")
    private String region;

    @Value("${app.aws.ssm.endpoint:}")
    private String endpoint;

    private volatile SsmClient client;

    public String getParameter(String name, String fallback) {
        if (!enabled || name == null || name.trim().isEmpty()) {
            return fallback;
        }
        String cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            String value = client().getParameter(
                    GetParameterRequest.builder().name(name).withDecryption(true).build())
                    .parameter().value();
            if (value != null && !value.isEmpty()) {
                cache.put(name, value);
                return value;
            }
        } catch (RuntimeException e) {
            // Parameter missing or SSM unreachable: fall back to externally configured value
        }
        return fallback;
    }

    private SsmClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    SsmClientBuilder builder = SsmClient.builder().region(Region.of(region));
                    if (endpoint != null && !endpoint.trim().isEmpty()) {
                        builder.endpointOverride(URI.create(endpoint.trim()));
                    }
                    client = builder.build();
                }
            }
        }
        return client;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
