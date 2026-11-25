package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Provides the Amazon S3 client (AWS SDK for Java v2) used for report and backup storage.
 * Credentials are resolved by the default provider chain (IAM role / environment).
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${app.aws.region:us-east-1}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }
}
