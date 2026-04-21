package com.example.orchestrator.service;

import com.example.orchestrator.messaging.JobPublisher;
import com.example.orchestrator.model.Job;
import com.example.orchestrator.store.JobStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class JobService {

    @Autowired
    private JobPublisher jobPublisher;

    public Job createJob(Job request) {
        String jobId = UUID.randomUUID().toString();

        request.setJobId(jobId);
        request.setStatus("SUBMITTED");
        request.setCreatedAt(Instant.now());

        JobStore.save(request);

        // Publish job request to ActiveMQ for worker to process
        jobPublisher.publishJobRequest(request);
        System.out.println("Service HIT");
        return request;
    }

    public Job getJob(String jobId) {
        return JobStore.get(jobId);
    }
}
