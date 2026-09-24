package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration that exposes an AWS SDK v2 {@link S3Client} bean.
 *
 * <p>The AWS region is read from the {@code aws.region} environment variable /
 * application property (default: {@code us-east-1}).  Credentials are resolved
 * automatically by the SDK's default credential-provider chain (IAM role,
 * environment variables, ~/.aws/credentials, etc.) — no hard-coded secrets.
 *
 * <p>Added as part of cr-java-0061 remediation: replacing hard-coded file-path
 * operations in {@link ReportService} with Amazon S3 object storage.
 */
@Configuration
public class S3Config {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and returns a singleton {@link S3Client} configured for the
     * target AWS region.  Credentials are supplied by the SDK default chain
     * (instance profile / environment variables) — no hard-coded values.
     *
     * @return a fully configured {@link S3Client}
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
