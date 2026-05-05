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

    private static final int MAX_RETRIES = 3;

    @JmsListener(destination = "copy.job.request")
    public void processJob(String message) {
        Job job = null;
        try {
            System.out.println("Worker received job!");
            job = objectMapper.readValue(message, Job.class);
            System.out.println("Processing: " + job.getJobId());

            job.setStatus("IN_PROGRESS");
            job.setMessage("Download started");
            job.setProgress(0);
            publisher.publishStatus(job);

            List<String> failedFiles = downloadFromS3(job);

            if (failedFiles.isEmpty()) {
                job.setStatus("COMPLETED");
                job.setMessage("Download successful");
                job.setProgress(100);
            } else {
                job.setStatus("COMPLETED_WITH_ERRORS");
                job.setMessage("Download completed with errors. Failed files: " + failedFiles);
                job.setProgress(100);
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
            List<String> paths = job.getPaths();
            int totalPaths = paths.size();
            int processedPaths = 0;

            for (String s3Path : paths) {
                if (s3Path == null || s3Path.isBlank()) {
                    throw new Exception("Invalid path: path cannot be empty");
                } else if (s3Path.equals("/") || s3Path.equals("*") || s3Path.equals(".")) {
                    System.out.println("WARNING: Downloading entire bucket. This may be large.");
                    allFailedFiles.addAll(downloadFolder(s3, job, ""));
                } else if (s3Path.endsWith("/")) {
                    allFailedFiles.addAll(downloadFolder(s3, job, s3Path));
                } else {
                    // Single file with retry
                    job.setProgress(0);
                    job.setMessage("Downloading file: " + s3Path);
                    publisher.publishStatus(job);

                    int attempt = 0;
                    boolean downloaded = false;

                    while (attempt < MAX_RETRIES && !downloaded) {
                        try {
                            attempt++;
                            System.out.println("Attempt " + attempt + " for: " + s3Path);
                            downloadFile(s3, job.getBucketName(), s3Path, job.getDestinationPath());
                            downloaded = true;
                            processedPaths++;
                            int progress = (int) ((processedPaths * 100) / totalPaths);
                            job.setProgress(progress);
                            job.setMessage("Downloaded: " + s3Path + " (" + processedPaths + "/" + totalPaths + " files)");
                            publisher.publishStatus(job);
                        } catch (Exception e) {
                            System.out.println("Attempt " + attempt + " failed for: " + s3Path + " → " + e.getMessage());
                            if (attempt >= MAX_RETRIES) {
                                System.out.println("Max retries reached for: " + s3Path + " → skipping");
                                allFailedFiles.add(s3Path);
                                processedPaths++;
                            } else {
                                try {
                                    Thread.sleep(2000L * attempt); // 2s, 4s backoff
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
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
            long fileCount = 0;

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

            // Count total files across all pages
            long totalFiles = listResponse.contents().stream()
                    .filter(o -> !o.key().endsWith("/") && !o.key().isBlank())
                    .count();

            ListObjectsV2Response tempResponse = listResponse;
            while (tempResponse.isTruncated()) {
                ListObjectsV2Request tempRequest = listRequest.toBuilder()
                        .continuationToken(tempResponse.nextContinuationToken())
                        .build();
                tempResponse = s3.listObjectsV2(tempRequest);
                totalFiles += tempResponse.contents().stream()
                        .filter(o -> !o.key().endsWith("/") && !o.key().isBlank())
                        .count();
            }

            System.out.println("Total files to download: " + totalFiles);
            long processedFiles = 0;

            // Reset to first page
            listResponse = s3.listObjectsV2(listRequest);

            while (true) {
                for (S3Object s3Object : listResponse.contents()) {
                    String key = s3Object.key();
                    if (key.endsWith("/") || key.isBlank()) continue;

                    if (++fileCount > maxFiles) {
                        throw new Exception("Too many files: exceeded limit of " + maxFiles + " files");
                    }

                    // ✅ Retry logic for each file
                    int attempt = 0;
                    boolean downloaded = false;

                    while (attempt < MAX_RETRIES && !downloaded) {
                        try {
                            attempt++;
                            System.out.println("Attempt " + attempt + " — File " + fileCount + ": " + key + " (" +
                                    String.format("%.2f", s3Object.size() / (1024.0 * 1024.0)) + " MB)");
                            downloadFile(s3, job.getBucketName(), key, job.getDestinationPath());
                            downloaded = true;
                        } catch (Exception e) {
                            System.out.println("Attempt " + attempt + " failed for: " + key + " → " + e.getMessage());
                            if (attempt >= MAX_RETRIES) {
                                System.out.println("Max retries reached for: " + key + " → skipping");
                                failedFiles.add(key);
                            } else {
                                try {
                                    Thread.sleep(2000L * attempt); // 2s, 4s backoff
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }

                    // ✅ Update progress after each file (success or skip)
                    processedFiles++;
                    int progress = (int) ((processedFiles * 100) / totalFiles);
                    job.setProgress(progress);
                    job.setMessage("Downloading... " + progress + "% (" + processedFiles + "/" + totalFiles + " files)");
                    publisher.publishStatus(job);
                    System.out.println("Progress: " + progress + "% (" + processedFiles + "/" + totalFiles + ")");
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

        return failedFiles;
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

            tempFile = destination.resolveSibling(
                    destination.getFileName() + "." + Thread.currentThread().getId() + ".tmp");

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
            if (header[0] != (byte) 0x89 || header[1] != 0x50 ||
                    header[2] != 0x4E || header[3] != 0x47) {
                throw new Exception("Corrupt PNG file: " + key + " (invalid header)");
            }
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            if (header[0] != (byte) 0xFF || header[1] != (byte) 0xD8 ||
                    header[2] != (byte) 0xFF) {
                throw new Exception("Corrupt JPEG file: " + key + " (invalid header)");
            }
        }
        System.out.println("Image valid: " + key);
    }

    private String calculateMd5(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (var is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}