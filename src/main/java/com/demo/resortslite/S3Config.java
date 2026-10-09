package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration for Amazon S3 client.
 *
 * <p>cr-java-0061 fix: Provides an {@link S3Client} bean used by {@link ReportService}
 * to replace all hard-coded absolute file path operations with Amazon S3 object storage.
 * The AWS region is resolved from the {@code aws.s3.region} property which is backed by
 * the {@code AWS_REGION} environment variable, ensuring no hard-coded infrastructure
 * values remain in the application source code.</p>
 *
 * <p>Credentials are resolved automatically by the AWS SDK v2 default credential provider
 * chain (IAM role attached to the ECS task / EC2 instance profile, environment variables
 * {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}, or {@code ~/.aws/credentials}
 * for local development).</p>
 */
@Configuration
public class S3Config {

    @Value("${aws.s3.region}")
    private String awsRegion;

    /**
     * Creates and exposes an {@link S3Client} bean configured for the target AWS region.
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
