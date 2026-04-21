package com.example.worker.messaging;

import com.example.worker.model.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
@Component
public class JobStatusPublisher {

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private ObjectMapper objectMapper;  // ← injected, not new ObjectMapper()

    public void publishStatus(Job job) {
        try {
            String message = objectMapper.writeValueAsString(job);
            jmsTemplate.convertAndSend("copy.job.status", message);
            System.out.println("Worker sent status: " + job.getStatus());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}