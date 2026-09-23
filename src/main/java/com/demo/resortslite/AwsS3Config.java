package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3 configuration bean.
 *
 * Provides a singleton {@link S3Client} that is used by {@link ReportService}
 * to replace hard-coded absolute file paths with Amazon S3 object storage
 * (remediation for cr-java-0061 – Hard-coded File Paths).
 *
 * The AWS region is resolved from the environment variable AWS_REGION or the
 * Spring property aws.region (defaults to us-east-1 when neither is set).
 * Credentials are resolved automatically by the AWS Default Credential Provider
 * Chain (IAM role, environment variables, ~/.aws/credentials, etc.).
 */
@Configuration
public class AwsS3Config {

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
