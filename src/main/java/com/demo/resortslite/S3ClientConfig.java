package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import org.springframework.beans.factory.annotation.Value;

/**
 * Spring configuration that exposes an AWS SDK v2 {@link S3Client} bean.
 *
 * <p>The AWS region is resolved from the {@code AWS_REGION} environment variable
 * (defaulting to {@code us-east-1} when not set). Credentials are automatically
 * resolved by the SDK's default credential provider chain, which supports:
 * <ul>
 *   <li>Environment variables ({@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY})</li>
 *   <li>AWS IAM instance profiles (EC2 / ECS task roles)</li>
 *   <li>AWS IAM roles for service accounts (EKS)</li>
 *   <li>~/.aws/credentials (local development)</li>
 * </ul>
 *
 * <p>This bean is consumed by {@link ReportService} to replace all hard-coded
 * absolute file-path operations (cr-java-0061) with Amazon S3 object storage.
 */
@Configuration
public class S3ClientConfig {

    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and returns a configured {@link S3Client} instance.
     *
     * @return a thread-safe S3Client ready for use across the application
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
