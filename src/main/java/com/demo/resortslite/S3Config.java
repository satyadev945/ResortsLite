package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration that exposes an AWS SDK v2 {@link S3Client} bean.
 *
 * <p>Credentials are resolved automatically by the AWS Default Credential Provider Chain
 * (IAM role, environment variables, ~/.aws/credentials, etc.) — no hard-coded secrets.
 *
 * <p>The AWS region is externalised via the {@code cloud.aws.region} property which can
 * be overridden at runtime through the {@code CLOUD_AWS_REGION} environment variable.
 */
@Configuration
public class S3Config {

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
