# GSUIF Backend Runbook

This is the backend service for the GSUIF project, built with Spring Boot 4.x, Java 21, and Maven.

## Getting Started

### Prerequisites
- Java 21 SDK
- Maven 3.9+ (or use the provided Maven Wrapper)
- Docker & Docker Compose (for local database)

### Running Locally with PostgreSQL
1. Start the local database using Docker Compose:
   ```bash
   docker-compose up -d
   ```
2. Run the application with the `local` profile:
   - **Using global Maven:**
     ```bash
     mvn spring-boot:run -Dspring-boot.run.profiles=local
     ```
   - **Using Maven Wrapper (Windows):**
     ```bash
     .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
     ```
   - **Using Maven Wrapper (Linux/macOS):**
     ```bash
     ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
     ```
3. The API will be available at `http://localhost:8080`.
4. The Swagger UI can be accessed at `http://localhost:8080/swagger-ui.html`.

### Running Tests with H2 (In-Memory)
To run automated tests (uses the `test` profile with an in-memory H2 database, requiring no external dependencies):
- **Using global Maven:**
  ```bash
  mvn clean test
  ```
- **Using Maven Wrapper (Windows):**
  ```bash
  .\mvnw.cmd clean test
  ```
- **Using Maven Wrapper (Linux/macOS):**
  ```bash
  ./mvnw clean test
  ```

### Main API Endpoints
- **Hello World Endpoint:** `GET /api/hello`
