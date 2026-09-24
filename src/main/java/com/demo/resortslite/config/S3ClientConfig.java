package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration for Amazon S3 client.
 *
 * <p>cr-java-0061: Provides an {@link S3Client} bean used by {@code ReportService}
 * to replace hard-coded absolute file path operations with Amazon S3 object storage.
 * The AWS region is resolved from the {@code cloud.aws.region} property (which itself
 * falls back to the {@code AWS_REGION} environment variable), following 12-factor
 * app principles for externalised configuration.</p>
 */
@Configuration
public class S3ClientConfig {

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and exposes an {@link S3Client} configured for the target AWS region.
     * Credentials are resolved automatically by the AWS Default Credential Provider Chain
     * (IAM role, environment variables, ~/.aws/credentials, etc.).
     *
     * @return a fully configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
