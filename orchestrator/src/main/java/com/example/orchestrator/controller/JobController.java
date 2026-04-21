package com.example.orchestrator.controller;

import com.example.orchestrator.model.Job;
import com.example.orchestrator.service.JobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/jobs")
public class JobController {

    @Autowired
    private JobService jobService;

    /**
     * Submit a new copy job
     * POST /jobs
     */
    @PostMapping
    public ResponseEntity<?> createJob(@RequestBody Job job) {
        Job createdJob = jobService.createJob(job);

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", createdJob.getJobId());
        response.put("status", createdJob.getStatus());
        System.out.println("Controller HIT");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);

    }

    /**
     * Get job status
     * GET /jobs/{jobId}
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId);

        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new HashMap<String, String>() {{
                put("error", "Job not found");
            }});
        }

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", job.getJobId());
        response.put("status", job.getStatus());

        if (job.getCompletedAt() != null) {
            response.put("completedAt", job.getCompletedAt());
        }

        if (job.getMessage() != null) {
            response.put("message", job.getMessage());
        }

        return ResponseEntity.ok(response);
    }
}

