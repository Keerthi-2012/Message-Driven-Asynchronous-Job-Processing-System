package com.example.worker.messaging;

import com.example.worker.model.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class JobRequestListener {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobStatusPublisher publisher;

    @Value("${aws.accessKeyId}")
    private String accessKeyId;

    @Value("${aws.secretAccessKey}")
    private String secretAccessKey;

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
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();

        try {
            for (String s3Path : job.getPaths()) {
                if (s3Path.endsWith("/")) {
                    // It's a folder — list all objects under this prefix
                    downloadFolder(s3, job, s3Path);
                } else {
                    // It's a single file
                    downloadFile(s3, job.getBucketName(), s3Path, job.getDestinationPath());
                }
            }
        } finally {
            s3.close();
        }
    }

    private void downloadFolder(S3Client s3, Job job, String prefix) throws Exception {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(job.getBucketName())
                .prefix(prefix)
                .build();

        ListObjectsV2Response listResponse;
        do {
            listResponse = s3.listObjectsV2(listRequest);
            for (S3Object s3Object : listResponse.contents()) {
                String key = s3Object.key();
                if (key.endsWith("/")) continue; // skip folder placeholders
                downloadFile(s3, job.getBucketName(), key, job.getDestinationPath());
            }
            listRequest = listRequest.toBuilder()
                    .continuationToken(listResponse.nextContinuationToken())
                    .build();
        } while (listResponse.isTruncated());
    }

    private void downloadFile(S3Client s3, String bucket, String key, String destinationBase) throws Exception {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        // Preserve folder structure under destination
        Path destination = Paths.get(destinationBase, key);
        Files.createDirectories(destination.getParent());

        Path tempFile = destination.resolveSibling(destination.getFileName() + ".tmp");
        s3.getObject(request, tempFile);
        Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Downloaded: " + key + " → " + destination);
    }
}