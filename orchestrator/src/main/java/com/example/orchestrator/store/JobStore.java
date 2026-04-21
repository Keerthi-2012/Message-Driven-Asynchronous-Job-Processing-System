package com.example.orchestrator.store;

import com.example.orchestrator.model.Job;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JobStore {
    private static final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public static void save(Job job) {
        jobs.put(job.getJobId(), job);
    }

    public static Job get(String jobId) {
        return jobs.get(jobId);
    }
}
