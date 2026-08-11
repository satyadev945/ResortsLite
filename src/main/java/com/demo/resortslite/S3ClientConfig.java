package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration that exposes an AWS SDK v2 {@link S3Client} bean.
 *
 * <p>The AWS region is externalised to the {@code AWS_REGION} environment variable
 * (or {@code app.aws.region} application property) so the application is portable
 * across AWS regions without code changes.  Credentials are resolved automatically
 * by the SDK's default credential-provider chain (IAM role, environment variables,
 * ~/.aws/credentials) — no hard-coded secrets are required.
 *
 * <p>This bean is required by {@link ReportService} which uses S3 to store reports
 * instead of writing to the local file system (cr-java-0061 fix).
 */
@Configuration
public class S3ClientConfig {

    @Value("${app.aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and returns a singleton {@link S3Client} configured for the target AWS region.
     * Uses the lightweight URL-connection HTTP client to avoid pulling in Netty or Apache HTTP.
     *
     * @return a fully configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }
}
