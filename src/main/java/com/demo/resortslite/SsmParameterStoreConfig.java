package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

/**
 * cr-java-0071 FIX: Centralised AWS Systems Manager Parameter Store accessor.
 *
 * <p>All environment-specific URLs (inventory service, report download base URL, etc.)
 * that were previously hard-coded in application source files are now retrieved at
 * runtime from AWS SSM Parameter Store. This enables environment-agnostic deployments:
 * the same application artefact can be deployed to dev, staging, and production simply
 * by populating the corresponding SSM parameters in each AWS account/region — no code
 * changes or rebuilds are required between environments.</p>
 *
 * <p>SSM parameters used by this application:</p>
 * <ul>
 *   <li>{@code /resortslite/inventory/service-url}   — inventory service endpoint URL</li>
 *   <li>{@code /resortslite/reports/download-base-url} — report download base URL</li>
 * </ul>
 *
 * <p>The AWS region is resolved from the {@code AWS_REGION} environment variable
 * (set automatically by ECS/EKS task definitions) with a fallback to
 * {@code cloud.aws.region.static} in {@code application.properties}.</p>
 */
@Component
public class SsmParameterStoreConfig {

    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Retrieves a plain-text parameter value from AWS Systems Manager Parameter Store.
     *
     * <p>The {@link SsmClient} is built using the default AWS credential provider chain,
     * which resolves credentials in the following order:
     * <ol>
     *   <li>Environment variables ({@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY})</li>
     *   <li>Java system properties</li>
     *   <li>AWS credentials file ({@code ~/.aws/credentials})</li>
     *   <li>ECS container credentials (IAM task role)</li>
     *   <li>EC2 instance profile / IMDSv2</li>
     * </ol>
     * No credentials are ever hard-coded in source code.</p>
     *
     * @param parameterName the fully-qualified SSM parameter name (e.g. {@code /resortslite/inventory/service-url})
     * @return the parameter value stored in SSM Parameter Store
     * @throws RuntimeException if the parameter does not exist or cannot be retrieved
     */
    public String getParameter(String parameterName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();

        } catch (ParameterNotFoundException e) {
            throw new RuntimeException(
                    "SSM parameter not found: " + parameterName
                    + ". Ensure the parameter is created in AWS Systems Manager Parameter Store "
                    + "for region " + awsRegion + ".", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve SSM parameter: " + parameterName, e);
        }
    }

    /**
     * Retrieves a SecureString parameter value from AWS Systems Manager Parameter Store,
     * decrypting it using the associated AWS KMS key.
     *
     * @param parameterName the fully-qualified SSM parameter name
     * @return the decrypted parameter value
     * @throws RuntimeException if the parameter does not exist or cannot be retrieved/decrypted
     */
    public String getSecureParameter(String parameterName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();

        } catch (ParameterNotFoundException e) {
            throw new RuntimeException(
                    "SSM SecureString parameter not found: " + parameterName
                    + ". Ensure the parameter is created in AWS Systems Manager Parameter Store "
                    + "for region " + awsRegion + ".", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve SSM SecureString parameter: " + parameterName, e);
        }
    }
}
