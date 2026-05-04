package com.example.worker.messaging;

import com.example.worker.model.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

@Component
public class JobRequestListener {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobStatusPublisher publisher;

    @JmsListener(destination = "copy.job.request")
    public void processJob(String message) {
        Job job = null;
        try {
            System.out.println("Worker received job!");
            job = objectMapper.readValue(message, Job.class);
            System.out.println("Processing: " + job.getJobId());

            job.setStatus("IN_PROGRESS");
            job.setMessage("Download started");
            publisher.publishStatus(job);


            downloadFromS3(job);

            job.setStatus("COMPLETED");
            job.setMessage("Download successful");
            job.setCompletedAt(Instant.now());
            publisher.publishStatus(job);

        } catch (Exception e) {
            e.printStackTrace();
            if (job != null) {
                job.setStatus("FAILED");
                job.setMessage(e.getMessage());
                job.setCompletedAt(Instant.now());
                publisher.publishStatus(job);
            }
        }
    }

private void downloadFromS3(Job job) throws Exception {
    S3Client s3 = S3Client.builder()
            .region(Region.of(job.getRegion()))
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .build();

    try {
        for (String s3Path : job.getPaths()) {
            if (s3Path == null || s3Path.isBlank()) {
                throw new Exception("Invalid path: path cannot be empty");
            } else if (s3Path.equals("/") || s3Path.equals("*") || s3Path.equals(".")) {
                System.out.println("WARNING: Downloading entire bucket. This may be large.");
                downloadFolder(s3, job, "");
            } else if (s3Path.endsWith("/")) {
                downloadFolder(s3, job, s3Path);
            } else {
                downloadFile(s3, job.getBucketName(), s3Path, job.getDestinationPath());
            }
        }
    } finally {
        s3.close();
    }
}

private void downloadFolder(S3Client s3, Job job, String prefix) throws Exception {
    try {
        long maxFiles = 1000;
        long maxBytes = 500L * 1024 * 1024; // 500MB
        long fileCount = 0;
        long totalBytes = 0;

        System.out.println("Starting folder download...");
        System.out.println("Prefix: " + (prefix.isEmpty() ? "entire bucket" : prefix));

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(job.getBucketName())
                .prefix(prefix)
                .build();

        ListObjectsV2Response listResponse = s3.listObjectsV2(listRequest);

        if (listResponse.contents().isEmpty()) {
            throw new Exception("No files found at path: " + prefix);
        }

        while (true) {
            for (S3Object s3Object : listResponse.contents()) {
                String key = s3Object.key();
                if (key.endsWith("/") || key.isBlank()) continue;

                if (++fileCount > maxFiles) {
                    throw new Exception("Too many files: exceeded limit of " + maxFiles + " files");
                }

                totalBytes += s3Object.size();
                if (totalBytes > maxBytes) {
                    throw new Exception("Total download size exceeded limit of 500MB");
                }

                System.out.println("File " + fileCount + ": " + key + " (" + s3Object.size() / (1024 * 1024) + " MB)");
                downloadFile(s3, job.getBucketName(), key, job.getDestinationPath());
            }

            if (!listResponse.isTruncated()) break;

            listRequest = listRequest.toBuilder()
                    .continuationToken(listResponse.nextContinuationToken())
                    .build();
            listResponse = s3.listObjectsV2(listRequest);
        }

    } catch (S3Exception e) {
        throw new Exception("S3 error for folder " + prefix + ": " + e.awsErrorDetails().errorMessage());
    }
}

private void downloadFile(S3Client s3, String bucket, String key, String destinationBase) throws Exception {
    try {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        Path destination = Paths.get(destinationBase, key);
        Files.createDirectories(destination.getParent());

        Path tempFile = destination.resolveSibling(destination.getFileName() + ".tmp");
        s3.getObject(request, tempFile);
        Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Downloaded: " + key + " → " + destination);

    } catch (NoSuchKeyException e) {
        throw new Exception("File not found in S3: " + key); // ✅ clean error
    } catch (S3Exception e) {
        throw new Exception("S3 error for file " + key + ": " + e.awsErrorDetails().errorMessage()); // ✅ clean S3 error
    }
}
}