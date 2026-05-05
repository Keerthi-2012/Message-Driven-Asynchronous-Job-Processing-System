package com.example.worker.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

@Component
public class CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);
    private static final String CHECKPOINT_DIR = "/downloads/.checkpoints";
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ✅ Save a completed file to checkpoint
    public void markFileCompleted(String jobId, String fileKey) {
        try {
            Path checkpointFile = getCheckpointPath(jobId);
            Files.createDirectories(checkpointFile.getParent());

            Set<String> completed = loadCompletedFiles(jobId);
            completed.add(fileKey);

            objectMapper.writeValue(checkpointFile.toFile(), completed);
            log.info("Checkpoint saved: jobId={} file={}", jobId, fileKey);
        } catch (Exception e) {
            log.error("Failed to save checkpoint: {}", e.getMessage());
        }
    }

    // ✅ Check if file already downloaded
    public boolean isFileCompleted(String jobId, String fileKey) {
        return loadCompletedFiles(jobId).contains(fileKey);
    }

    // ✅ Load completed files for a job
    public Set<String> loadCompletedFiles(String jobId) {
        try {
            Path checkpointFile = getCheckpointPath(jobId);
            if (Files.exists(checkpointFile)) {
                return objectMapper.readValue(
                    checkpointFile.toFile(),
                    objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class)
                );
            }
        } catch (Exception e) {
            log.error("Failed to load checkpoint: {}", e.getMessage());
        }
        return new HashSet<>();
    }

    // ✅ Delete checkpoint when job is done
    public void clearCheckpoint(String jobId) {
        try {
            Path checkpointFile = getCheckpointPath(jobId);
            Files.deleteIfExists(checkpointFile);
            log.info("Checkpoint cleared for jobId={}", jobId);
        } catch (Exception e) {
            log.error("Failed to clear checkpoint: {}", e.getMessage());
        }
    }

    private Path getCheckpointPath(String jobId) {
        return Paths.get(CHECKPOINT_DIR, jobId + ".json");
    }
}