# Message Driven Asynchronous Job Processing System

## Overview
This project implements a **distributed asynchronous job processing system** using a microservices architecture.

It demonstrates how to:
- Decouple services using a message queue
- Process tasks asynchronously in the background
- Track job status without blocking the client

Users can submit file copy jobs and monitor their progress while processing happens independently.

---

## Architecture

The system consists of three main services:

### 1. Orchestrator Service
- Handles incoming REST API requests
- Generates unique job IDs
- Stores job status in memory
- Sends job requests to the message queue
- Listens for status updates from workers

### 2. ActiveMQ Service
- Acts as the **message broker**
- Maintains:
  - Job request queue
  - Job status update queue
- Enables decoupling between services

### 3. Worker Service
- Consumes jobs from the queue
- Downloads files/folders from public AWS S3
- Saves data locally
- Sends status updates back to the orchestrator


## Flow of Execution

1. Client submits a job via REST API  
2. Orchestrator:
   - Generates a job ID
   - Pushes request to message queue  
3. Worker:
   - Picks up job asynchronously
   - Starts processing  
4. Worker downloads data from S3 and stores it locally  
5. Worker sends status updates:
   - `IN_PROGRESS`
   - `COMPLETED`  
6. Orchestrator updates job status  
7. Client checks job status via API  


## Features

- Microservice-based architecture  
- Asynchronous job processing  
- Message queue integration (ActiveMQ)  
- Docker-based deployment  
- Supports file and folder downloads from S3  
- In-memory job tracking (no database required)  

---

## 🛠️ Tech Stack

- **Java (Spring Boot)**
- **ActiveMQ**
- **Docker & Docker Compose**
- **AWS S3**

---

## API Endpoints

### ➤ Submit Job

**POST** `/jobs`

#### Request Body
```json
{
  "bucket": "your-bucket-name",
  "region": "your-region",
  "path": "file-or-folder-path",
  "destination": "/local/path"
}
