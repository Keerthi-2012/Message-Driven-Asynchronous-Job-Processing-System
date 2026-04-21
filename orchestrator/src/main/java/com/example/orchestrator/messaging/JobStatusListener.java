package com.example.orchestrator.messaging;

import com.example.orchestrator.model.Job;
import com.example.orchestrator.store.JobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class JobStatusListener {

    @Autowired
    private ObjectMapper objectMapper;  // ← injected, not new ObjectMapper()

    @JmsListener(destination = "copy.job.status")
    public void handleJobStatus(String message) {
        try {
            System.out.println("Orchestrator received status message: " + message);

            Job statusUpdate = objectMapper.readValue(message, Job.class);
            Job existingJob = JobStore.get(statusUpdate.getJobId());

            if (existingJob != null) {
                existingJob.setStatus(statusUpdate.getStatus());
                existingJob.setMessage(statusUpdate.getMessage());

                if ("COMPLETED".equals(statusUpdate.getStatus())) {
                    existingJob.setCompletedAt(Instant.now());
                }

                JobStore.save(existingJob);
                System.out.println("Job " + statusUpdate.getJobId() + " updated to: " + statusUpdate.getStatus());
            } else {
                System.err.println("Job not found in store: " + statusUpdate.getJobId());
            }

        } catch (Exception e) {
            System.err.println("Error processing job status: " + e.getMessage());
            e.printStackTrace();  // now you'll actually see what's failing
        }
    }
}