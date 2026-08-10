package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Stores generated report data in Azure Blob Storage instead of local disk.
 */
@Service
public class AzureBlobReportStorage {

    private final BlobContainerClient containerClient;
    private final String containerName;

    public AzureBlobReportStorage(
            @Value("${azure.storage.blob.endpoint:}") String blobEndpoint,
            @Value("${azure.storage.blob.container:resort-reports}") String containerName) {
        this.containerName = containerName;
        if (StringUtils.hasText(blobEndpoint)) {
            this.containerClient = new BlobContainerClientBuilder()
                    .endpoint(blobEndpoint)
                    .containerName(containerName)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        } else {
            this.containerClient = null;
        }
    }

    public String uploadReport(String blobName, String content) {
        if (containerClient == null) {
            throw new IllegalStateException("Azure Blob Storage endpoint is not configured");
        }
        if (!containerClient.exists()) {
            containerClient.create();
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        BlobClient blobClient = containerClient.getBlobClient(blobName);
        blobClient.upload(new ByteArrayInputStream(bytes), bytes.length, true);
        return blobClient.getBlobUrl();
    }

    public String getContainerName() {
        return containerName;
    }
}
