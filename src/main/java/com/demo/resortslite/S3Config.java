package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Provides an Amazon S3 client using the default AWS credentials provider chain
 * (IAM role / IRSA / environment credentials).
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${app.aws.region}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }
}
