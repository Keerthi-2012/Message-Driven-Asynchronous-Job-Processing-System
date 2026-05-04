package com.example.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class WorkerApplication {

    public static void main(String[] args) {
        cleanupTempFiles("/downloads");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Worker shutting down - cleaning up temp files...");
            cleanupTempFiles("/downloads");
        }));

        SpringApplication.run(WorkerApplication.class, args);
    }

    private static void cleanupTempFiles(String directory) {
        try {
            Path downloadPath = Paths.get(directory);
            if (Files.exists(downloadPath)) {
                Files.walk(downloadPath)
                    .filter(p -> p.toString().endsWith(".tmp"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            System.out.println("Cleaned up temp file: " + p);
                        } catch (Exception e) {
                            System.out.println("Failed to delete temp file: " + p);
                        }
                    });
            }
        } catch (Exception e) {
            System.out.println("Temp cleanup error: " + e.getMessage());
        }
    }
}