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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipException;

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

        List<String> failedFiles = downloadFromS3(job);

        if (failedFiles.isEmpty()) {
            job.setStatus("COMPLETED");
            job.setMessage("Download successful");
        } else {
            job.setStatus("COMPLETED_WITH_ERRORS");
            job.setMessage("Download completed with errors. Failed files: " + failedFiles);
        }

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
private List<String> downloadFromS3(Job job) throws Exception {
    S3Client s3 = S3Client.builder()
            .region(Region.of(job.getRegion()))
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .build();

    List<String> allFailedFiles = new ArrayList<>();

    try {
        for (String s3Path : job.getPaths()) {
            if (s3Path == null || s3Path.isBlank()) {
                throw new Exception("Invalid path: path cannot be empty");
            } else if (s3Path.equals("/") || s3Path.equals("*") || s3Path.equals(".")) {
                System.out.println("WARNING: Downloading entire bucket. This may be large.");
                allFailedFiles.addAll(downloadFolder(s3, job, ""));
            } else if (s3Path.endsWith("/")) {
                allFailedFiles.addAll(downloadFolder(s3, job, s3Path));
            } else {
                downloadFile(s3, job.getBucketName(), s3Path, job.getDestinationPath());
            }
        }
    } finally {
        s3.close();
    }

    return allFailedFiles;
}
private List<String> downloadFolder(S3Client s3, Job job, String prefix) throws Exception {
    List<String> failedFiles = new ArrayList<>();
    try {
        long maxFiles = 1000;
        long maxBytes = 500L * 1024 * 1024;
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

                try {
                    System.out.println("File " + fileCount + ": " + key + " (" + s3Object.size() / (1024 * 1024) + " MB)");
                    downloadFile(s3, job.getBucketName(), key, job.getDestinationPath());
                } catch (Exception e) {
                    System.out.println("Skipping corrupt/failed file: " + key + " → " + e.getMessage());
                    failedFiles.add(key);
                }
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

    return failedFiles; // ✅ return instead of throw
}
    private void downloadFile(S3Client s3, String bucket, String key, String destinationBase) throws Exception {
        Path tempFile = null;
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            Path destination = Paths.get(destinationBase, key);
            Files.createDirectories(destination.getParent());

            tempFile = destination.resolveSibling(destination.getFileName() + ".tmp");

            // Download file and get response metadata
            GetObjectResponse response = s3.getObject(request, tempFile);

            // Get ETag from S3 (MD5 hash for non-multipart uploads)
            String etag = response.eTag().replace("\"", "");

            // Skip checksum for multipart uploads (etag contains "-")
            if (!etag.contains("-")) {
                String localMd5 = calculateMd5(tempFile);
                if (!etag.equalsIgnoreCase(localMd5)) {
                    throw new Exception("File corrupted during download: " + key + " (checksum mismatch)");
                }
            }

            // Validate file content based on extension
            validateFile(tempFile, key);

            Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Downloaded: " + key + " → " + destination);

        } catch (NoSuchKeyException e) {
            throw new Exception("File not found in S3: " + key);
        } catch (S3Exception e) {
            throw new Exception("S3 error for file " + key + ": " + e.awsErrorDetails().errorMessage());
        } finally {
            // Cleanup temp file on any failure
            if (tempFile != null && Files.exists(tempFile)) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private void validateFile(Path file, String key) throws Exception {
        String lower = key.toLowerCase();

        if (lower.endsWith(".zip")) {
            validateZip(file, key);
        } else if (lower.endsWith(".pdf")) {
            validatePdf(file, key);
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            validateImage(file, key);
        }
        // other formats pass through without validation
    }

    private void validateZip(Path file, String key) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() == 0) {
                throw new Exception("Corrupt zip file: " + key + " (empty or invalid)");
            }
            System.out.println("Zip valid: " + key + " (" + zip.size() + " entries)");
        } catch (ZipException e) {
            throw new Exception("Corrupt zip file: " + key + " → " + e.getMessage());
        }
    }

    private void validatePdf(Path file, String key) throws Exception {
        byte[] header = new byte[4];
        try (var is = Files.newInputStream(file)) {
            is.read(header);
        }
        // PDF files always start with %PDF
        if (!new String(header).startsWith("%PDF")) {
            throw new Exception("Corrupt PDF file: " + key + " (invalid header)");
        }
        System.out.println("PDF valid: " + key);
    }

    private void validateImage(Path file, String key) throws Exception {
        byte[] header = new byte[8];
        try (var is = Files.newInputStream(file)) {
            is.read(header);
        }
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) {
            // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
            if (header[0] != (byte) 0x89 || header[1] != 0x50 ||
                header[2] != 0x4E || header[3] != 0x47) {
                throw new Exception("Corrupt PNG file: " + key + " (invalid header)");
            }
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            // JPEG magic bytes: FF D8 FF
            if (header[0] != (byte) 0xFF || header[1] != (byte) 0xD8 ||
                header[2] != (byte) 0xFF) {
                throw new Exception("Corrupt JPEG file: " + key + " (invalid header)");
            }
        }
        System.out.println("Image valid: " + key);
    }

    private String calculateMd5(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}