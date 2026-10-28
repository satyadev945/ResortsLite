package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS Systems Manager (SSM) Parameter Store configuration.
 *
 * <p>Provides an {@link SsmClient} bean used by application components to retrieve
 * environment-specific URLs and configuration values from AWS SSM Parameter Store,
 * replacing hard-coded environment URLs (cr-java-0071).
 *
 * <p>The AWS region is read from the {@code AWS_REGION} environment variable
 * (default: {@code us-east-1}). Credentials are resolved automatically by the
 * AWS Default Credential Provider Chain (IAM role, environment variables,
 * {@code ~/.aws/credentials}).
 */
@Configuration
public class AwsSsmConfig {

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and configures an {@link SsmClient} for the configured AWS region.
     *
     * @return a fully configured {@link SsmClient} instance
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
