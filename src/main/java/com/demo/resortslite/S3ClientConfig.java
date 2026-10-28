package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * cr-java-0061 FIX: Spring configuration that exposes an AWS SDK v2 {@link S3Client} bean.
 *
 * <p>The AWS region is externalised via the {@code AWS_REGION} environment variable (or the
 * {@code aws.region} application property) so that no region is hard-coded in the binary.
 * Credentials are resolved automatically by the SDK's
 * {@link software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider}, which honours
 * the standard AWS credential chain (environment variables → instance profile → ECS task role
 * → EC2 metadata service), making the application fully cloud-native on AWS.
 */
@Configuration
public class S3ClientConfig {

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and exposes a singleton {@link S3Client} configured for the target AWS region.
     * Credentials are resolved via the SDK's default credential provider chain.
     *
     * @return a configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
