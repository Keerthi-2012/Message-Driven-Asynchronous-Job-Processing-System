package com.example.orchestrator.model;

import java.time.Instant;
import java.util.List;

public class Job {
    private String jobId;
    private String bucketName;
    private String region;
    private List<String> paths;
    private String destinationPath;
    private String status;
    private Instant createdAt;
    private Instant completedAt;
    private String message;

    // Constructors
    public Job() {
    }

    public Job(String jobId, String bucketName, String region, List<String> paths, String destinationPath, String status, Instant createdAt, Instant completedAt) {
        this.jobId = jobId;
        this.bucketName = bucketName;
        this.region = region;
        this.paths = paths;
        this.destinationPath = destinationPath;
        this.status = status;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    // Getters and Setters
    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getDestinationPath() {
        return destinationPath;
    }

    public void setDestinationPath(String destinationPath) {
        this.destinationPath = destinationPath;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
