package com.example.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.activemq.broker-url=vm://localhost?broker.persistent=false",
    "spring.jms.listener.auto-startup=false"
})
class WorkerApplicationTests {

    @Test
    void contextLoads() {
    }

}
