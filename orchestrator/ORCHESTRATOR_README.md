# Message Driven Asynchronous Job Processing System

## Project Overview

This is the **Orchestrator Service** for a distributed copy system. It handles:
- REST API for submitting copy jobs
- Publishing job requests to ActiveMQ message queue
- Consuming job status updates from ActiveMQ
- Maintaining in-memory job status store

## Architecture

```
Client
   |
   |  (REST API: POST /jobs, GET /jobs/{jobId})
   v
Orchestrator Service
   |
   |  (Job Request Message)
   v
ActiveMQ Queue: copy.job.request
   ^
   |  (Consumes job)
   |
Worker Service
   |
   |  (Status Update Message)
   v
ActiveMQ Queue: copy.job.status
   ^
   |  (Consumes status)
   |
Orchestrator Service
```

## Project Structure

```
src/main/java/com/example/orchestrator/
 ├── OrchestratorApplication.java      (Spring Boot main application)
 ├── config/
 │   └── ActiveMQConfig.java            (JMS configuration)
 ├── controller/
 │   └── JobController.java             (REST API endpoints)
 ├── service/
 │   └── JobService.java                (Business logic)
 ├── model/
 │   └── Job.java                       (Job data model)
 ├── store/
 │   └── JobStore.java                  (In-memory job store)
 └── messaging/
     ├── JobPublisher.java              (Publishes messages to ActiveMQ)
     └── JobStatusListener.java         (Consumes status updates from ActiveMQ)
```

## REST API Endpoints

### 1. Submit a Copy Job
**Endpoint:** `POST /jobs`

**Request Body:**
```json
{
  "bucketName": "example-public-bucket",
  "region": "us-east-1",
  "paths": ["data/file1.csv", "logs/2024/"],
  "destinationPath": "/downloads"
}
```

**Response (201 Created):**
```json
{
  "jobId": "uuid-1234",
  "status": "SUBMITTED"
}
```

### 2. Check Job Status
**Endpoint:** `GET /jobs/{jobId}`

**Response Example (In Progress):**
```json
{
  "jobId": "uuid-1234",
  "status": "IN_PROGRESS"
}
```

**Response Example (Completed):**
```json
{
  "jobId": "uuid-1234",
  "status": "COMPLETED",
  "completedAt": "2026-04-17T10:35:00Z"
}
```

## Building & Running

### Prerequisites
- Docker & Docker Compose
- Java 17 (for local development)
- Maven (for local development)

### Using Docker Compose

```bash
# Build and start all services
docker-compose up --build

# View logs
docker-compose logs -f orchestrator

# Stop all services
docker-compose down
```

### Local Development

```bash
# Build the project
mvn clean install

# Run the application (requires ActiveMQ running)
mvn spring-boot:run
```

## ActiveMQ Queues

- **copy.job.request**: Queue for job requests sent from Orchestrator to Worker
- **copy.job.status**: Queue for status updates sent from Worker back to Orchestrator

### ActiveMQ Web Console
- URL: `http://localhost:8161/admin/`
- Username: `admin`
- Password: `admin`

## Job Lifecycle

1. **SUBMITTED**: Job received and stored in memory
2. **IN_PROGRESS**: Worker is downloading files
3. **COMPLETED**: Job finished successfully
4. **FAILED**: Job encountered an error

## Configuration

Configuration is managed via `application.properties`:

```properties
spring.application.name=orchestrator
server.port=8080
spring.activemq.broker-url=tcp://activemq:61616
spring.activemq.user=admin
spring.activemq.password=admin
```

## Worker Service Integration

The Worker Service will:
1. Listen to `copy.job.request` queue
2. Download files/folders from AWS S3 (public buckets)
3. Save files to the specified destination path
4. Send status updates to `copy.job.status` queue

## Notes

- Job data is stored in-memory only (data is lost on service restart)
- No database is used (as per requirements)
- Public S3 buckets only (no AWS credentials required)
- Thread-safe in-memory store using `ConcurrentHashMap`
