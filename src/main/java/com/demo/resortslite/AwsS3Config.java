package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3 configuration — provides an S3Client bean used by ReportService
 * to replace hard-coded local file-system paths (cr-java-0061).
 *
 * The AWS region is read from the environment variable AWS_REGION
 * (default: us-east-1). Credentials are resolved automatically by the
 * AWS Default Credential Provider Chain (IAM role, env vars, ~/.aws/credentials).
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
