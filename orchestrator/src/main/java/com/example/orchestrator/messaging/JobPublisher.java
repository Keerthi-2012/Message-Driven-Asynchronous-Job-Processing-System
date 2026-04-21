package com.example.orchestrator.messaging;

import com.example.orchestrator.model.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
public class JobPublisher {

    @Autowired
    private JmsTemplate jmsTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public void publishJobRequest(Job job) {
        try {

            String message = objectMapper.writeValueAsString(job);
            jmsTemplate.convertAndSend("copy.job.request", message);
            System.out.println("Publisher HIT");
            System.out.println("Sending message to MQ...");
            System.out.println("Published job request: " + job.getJobId());
        } catch (Exception e) {
            System.err.println("Error publishing job request: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
