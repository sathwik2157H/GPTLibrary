# GPTLibrary

GPTLibrary is an enterprise-grade Java 17 Spring Boot RAG (Retrieval-Augmented Generation) backend powered by Google Gemini and PostgreSQL 16 with the `pgvector` extension.

---

## Architecture & Technology Stack

- **Java:** 17 (OpenJDK Temurin)
- **Framework:** Spring Boot 3.5.16
- **AI / Embeddings:** Spring AI 1.1.8
  - **Chat Model:** `gemini-3.5-flash-lite` (configurable via `GEMINI_CHAT_MODEL`, e.g., `gemini-2.5-flash`)
  - **Embedding Model:** `gemini-embedding-001` (768 output dimensions, client-side unit $L_2$ normalized)
- **Vector Database:** PostgreSQL 16 with `pgvector` extension (Docker containerized)
- **Database Migrations:** Flyway (`V1`, `V2`, `V3`)
- **Document Processing:** Apache Tika 3.3.3 (PDF, DOCX, TXT)
- **Containerization:** Docker Compose with persistent named volume and health checks

---

## Prerequisites

1. **Java 17 JDK** (set `JAVA_HOME=D:\Java` and ensure it appears before other JDKs in `PATH`)
2. **Apache Maven 3.9+** (`D:\apache-maven-3.9.16`)
3. **Docker Desktop** running with WSL2 backend
4. **Gemini API Key** configured as an environment variable:
   ```powershell
   [System.Environment]::SetEnvironmentVariable("GEMINI_API_KEY", "your-key-here", "User")
   ```

---

## Getting Started

### 1. Start the PostgreSQL + pgvector Database
In the project root directory, run:
```powershell
docker compose up -d
```
Verify the container status and health check:
```powershell
docker compose ps
```
The status should report `healthy`.

### 2. Build and Test the Application
Build and test the application using Maven and Java 17:
```powershell
$env:JAVA_HOME = "D:\Java"
$env:PATH = "D:\apache-maven-3.9.16\bin;D:\Java\bin;" + $env:PATH
mvn clean test
```

### 3. Run the Application
Start the Spring Boot backend locally:
```powershell
mvn spring-boot:run
```
The application will start on port `8080`.

---

## API Endpoints

### 1. Health Endpoints
- **Application Health:** `GET http://localhost:8080/api/v1/health`
- **Actuator Health:** `GET http://localhost:8080/actuator/health`

### 2. Document Ingestion Endpoints
- **Upload Document:** `POST /api/v1/documents/upload` (multipart/form-data with `file`)
  - Supported formats: `.pdf`, `.docx`, `.txt` (max 50 MB)
- **List Documents:** `GET /api/v1/documents`
- **Get Document by ID:** `GET /api/v1/documents/{id}`

### 3. RAG Retrieval & Generation Endpoint
- **Query Endpoint:** `POST /api/v1/rag/query` (Content-Type: `application/json`)
  - **Request Body:**
    ```json
    {
      "question": "What is pgvector?",
      "topK": 4,
      "similarityThreshold": 0.65
    }
    ```
  - **Response Body:**
    ```json
    {
      "question": "What is pgvector?",
      "answer": "pgvector is a vector similarity search extension for PostgreSQL...",
      "sources": [
        {
          "documentId": "4adae0bf-04d3-4793-b92a-94d7026b3ed1",
          "filename": "database-guide.pdf",
          "chunkIndex": 0,
          "score": 0.892,
          "excerpt": "PostgreSQL pgvector provides vector similarity search capabilities..."
        }
      ],
      "retrievedChunkCount": 1,
      "insufficientEvidence": false
    }
    ```
  - **`insufficientEvidence` Contract & Limitation:**
    The `insufficientEvidence` boolean flag is a heuristic absence and refusal indicator. It evaluates to `true` when:
    - Zero valid vector chunks are retrieved above the requested `similarityThreshold` (or all chunks belong to unindexed/failed documents).
    - The LLM's generated response contains explicit refusal or missing-fact phrases (e.g. *"the document does not mention..."*, *"insufficient evidence..."*), while distinguishing concessive/contrasting preambles (e.g. *"While earlier drafts did not mention X, Section 4 confirms Y"* evaluates to `false`).
    - *Known Limitation:* This flag is a heuristic signal based on syntactic patterns and retrieval presence; it does **not** provide a mathematical guarantee against confident, unhedged hallucinations where the LLM asserts ungrounded claims without expressing uncertainty.

---

## Database Migrations (Flyway)

Database migrations are tracked in `src/main/resources/db/migration/`:
- `V1__create_documents_table.sql`: Manages metadata and processing status for uploaded documents (`PROCESSING`, `INDEXED`, `FAILED`).
- `V2__create_vector_store_table.sql`: Creates the `vector_store` table with `VECTOR(768)` embedding support and an HNSW cosine similarity index.
- `V3__add_vector_store_document_id_index.sql`: B-tree index on `(metadata->>'document_id')` for fast vector cleanup and filtered retrieval.

---

## Configuration & Environment Variables

| Variable | Default Value | Description |
|---|---|---|
| `GEMINI_API_KEY` | *(None - Required for live API)* | Google Gemini API Key |
| `GEMINI_CHAT_MODEL` | `gemini-3.5-flash-lite` | Gemini Chat Model (e.g., `gemini-2.5-flash`) |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `gptlibrary` | Database name |
| `DB_USER` | `gptuser` | Database user |
| `DB_PASSWORD` | `gptpassword` | Database password |

---

## Project Status

- [x] **Phase 1:** Environment setup, containerized vector database, application skeleton, health endpoints, Flyway migrations, and integration testing.
- [x] **Phase 2:** Document ingestion pipeline with Apache Tika parsing (PDF, DOCX, TXT), chunking (512 tokens / 50 overlap), unit $L_2$ normalized 768-dim embeddings, transactional status persistence, and orphaned vector cleanup.
- [x] **Phase 3:** RAG retrieval pipeline with cosine similarity search, failed document isolation, Gemini chat prompt generation (`gemini-3.5-flash-lite`), structured source citations, and heuristic insufficient-evidence handling.
- [ ] **Phase 4:** Observability, error handling, SSE streaming, and user interface.