package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration that provides an AWS SDK v2 {@link S3Client} bean.
 *
 * <p>The AWS region is resolved from the {@code AWS_REGION} environment variable
 * (or the {@code aws.region} Spring property), defaulting to {@code us-east-1}.
 * Credentials are resolved automatically by the
 * {@link software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider}
 * chain (IAM role → environment variables → ~/.aws/credentials), which is the
 * recommended approach for cloud-native deployments on AWS (ECS, EKS, EC2, Lambda).
 *
 * <p>cr-java-0061: This bean is required to support the S3-based report storage
 * that replaces the hard-coded absolute file paths in {@link com.demo.resortslite.ReportService}.
 */
@Configuration
public class S3ClientConfig {

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and returns a configured {@link S3Client} instance.
     * Credentials are resolved via the default AWS credential provider chain,
     * which supports IAM roles, environment variables, and instance profiles.
     *
     * @return a fully configured S3Client
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
