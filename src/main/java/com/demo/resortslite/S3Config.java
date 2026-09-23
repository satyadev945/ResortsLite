package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3 client configuration.
 *
 * <p>Provides a singleton {@link S3Client} bean that is used by {@link ReportService}
 * to store and retrieve report objects from Amazon S3, replacing all hard-coded
 * local file-system paths (cr-java-0061).</p>
 *
 * <p>The AWS region is resolved from the {@code aws.region} application property,
 * which in turn defaults to the {@code AWS_REGION} environment variable so that
 * the value is never hard-coded in source code.</p>
 */
@Configuration
public class S3Config {

    /**
     * AWS region resolved from environment variable {@code AWS_REGION} or
     * application property {@code aws.region} (default: {@code us-east-1}).
     */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and configures an AWS SDK v2 {@link S3Client} using the
     * URL-connection HTTP client for Java 8 compatibility.
     *
     * <p>Credentials are resolved automatically by the
     * {@code DefaultCredentialsProvider} chain (environment variables,
     * EC2/ECS instance profile, AWS config file, etc.) — no credentials
     * are hard-coded in source.</p>
     *
     * @return a configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }
}
