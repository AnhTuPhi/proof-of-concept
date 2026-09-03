# Chunked Upload / Download POC

Two transfer modes side by side, so you can compare:

| Mode | Data path | What touches the server |
|---|---|---|
| **Local** | Browser → Spring → `./storage/` on disk | All bytes flow through your app server |
| **S3 / MinIO direct** | Browser → MinIO (port 9000) | Spring only mints pre-signed URLs; bytes never touch it |

Both share the same UI for parallel chunking, retry, per-chunk progress, and parallel range download.

## Requirements

- JDK 21 (auto-detected at `C:\Users\you\.jdks\graalvm-jdk-21.0.4` on this machine)
- Maven (system install or the included wrapper)
- Docker (only if you want to try S3 mode against MinIO)

Set `JAVA_HOME` before launching:

```cmd
set JAVA_HOME=C:\Users\tu.phianh\.jdks\graalvm-jdk-21.0.4
```

## Run

### 1. Local mode only (no extra setup)

```cmd
D:\apache-maven-3.8.4\bin\mvn.cmd spring-boot:run
```

Open <http://localhost:8080>, leave the mode toggle on **Local** and upload.

### 2. Local + S3/MinIO mode

```cmd
docker compose up -d
D:\apache-maven-3.8.4\bin\mvn.cmd spring-boot:run
```

Spring auto-creates the `poc-uploads` bucket and applies a CORS policy on first start. Toggle the UI to **S3 / MinIO direct** and upload again. MinIO console: <http://localhost:9001> (admin / admin12345).

## What to watch in DevTools → Network

**Local mode (current):**
```
POST  http://localhost:8080/api/uploads/init
PUT   http://localhost:8080/api/uploads/{id}/chunks/0
PUT   http://localhost:8080/api/uploads/{id}/chunks/1
…  (N parallel — bytes go through Spring)
POST  http://localhost:8080/api/uploads/{id}/complete
```

**S3 mode:**
```
POST  http://localhost:8080/api/s3/uploads/init        ← Spring: tiny JSON
POST  http://localhost:8080/api/s3/uploads/sign-parts  ← Spring: tiny JSON
PUT   http://localhost:9000/poc-uploads/uploads/…?X-Amz-Signature=…   ← MinIO directly
PUT   http://localhost:9000/poc-uploads/uploads/…?X-Amz-Signature=…   ← (N parallel)
POST  http://localhost:8080/api/s3/uploads/complete    ← Spring: tiny JSON
```

Watch the **size** column for the PUT requests — that's where the file bytes actually flow.

## API

### Local mode

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/uploads/init` | Create session. `{filename, contentType, totalSize, chunkSize}` → `{uploadId, totalChunks}` |
| `PUT`  | `/api/uploads/{id}/chunks/{index}` | One chunk, raw body. Idempotent. |
| `GET`  | `/api/uploads/{id}/status` | Completed chunk indices for resume. |
| `POST` | `/api/uploads/{id}/complete` | Reassemble + SHA-256 + register. |
| `GET`  | `/api/files` | List finalized files. |
| `HEAD` | `/api/files/{id}` | Probe size + range support. |
| `GET`  | `/api/files/{id}` | Download. Supports `Range:`. Returns 206 for partial. |

### S3 mode

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/s3/uploads/init` | `CreateMultipartUpload` → `{uploadId, key, partCount}` |
| `POST` | `/api/s3/uploads/sign-parts` | Mint pre-signed PUT URLs (one per part, 1h TTL) |
| `POST` | `/api/s3/uploads/complete` | `CompleteMultipartUpload` with `[{partNumber, etag}]` |
| `POST` | `/api/s3/uploads/abort` | `AbortMultipartUpload` (cleanup) |
| `GET`  | `/api/s3/files` | `ListObjectsV2` under `uploads/` prefix |
| `GET`  | `/api/s3/files/signed-get?key=…` | Mint pre-signed GET URL (range-capable) |

## Code map

```
src/main/java/com/poc/upload/
├── UploadPocApplication.java           Spring Boot entry point
├── config/S3Config.java                MinIO/S3 client + presigner; bootstraps bucket & CORS
├── controller/
│   ├── UploadController.java           Local-mode upload endpoints
│   ├── DownloadController.java         Local-mode download with Range support
│   └── S3UploadController.java         S3-mode endpoints (init / sign / complete / list / signed-get)
├── service/
│   ├── UploadSessionService.java       Local: streams chunks to ./storage/temp, reassembles, SHA-256s
│   ├── FileStorageService.java         Local: finalized file registry
│   └── S3UploadService.java            S3 client wrapper (multipart calls + presigning)
└── model/
    ├── UploadSession.java
    └── StoredFile.java
src/main/resources/
├── application.yml                     Multipart disabled; S3 endpoint = http://localhost:9000
└── static/index.html                   Mode toggle + parallel upload/download for both paths
docker-compose.yml                      MinIO single-node, ports 9000 (S3) / 9001 (console)
```

## Storage layout

**Local mode:**
```
./storage/
├── temp/{uploadId}/{i}.chunk     # in-flight chunks (deleted on complete)
└── files/{fileId}/data.bin       # finalized files
```

**S3 mode (MinIO):**
```
bucket: poc-uploads
keys:   uploads/{uuid}/{filename}
```

## Tuning

- `application.yml` → `poc.storage-dir` (local) and `poc.s3.*` (MinIO endpoint, bucket, credentials)
- UI → chunk size dropdown (1–32 MB) and concurrency (1–12). S3 mode enforces ≥5 MB

## What this POC deliberately leaves out

| Missing | Add for production |
|---|---|
| Persistent state (local mode sessions are in-memory) | Database for upload sessions + file metadata |
| Auth | JWT — token on every chunk PUT, scoped per user |
| Cleanup cron | Sweep `./storage/temp/*` ; abort orphaned S3 multipart uploads (otherwise S3 keeps charging) |
| Resume across refresh | Persist `{uploadId, key, completed parts}` to IndexedDB |
| Web Worker hashing | Move SHA-256 off the main thread; upload concurrent with hashing |
| File System Access API | Stream download chunks straight to disk; no 2 GB Blob ceiling |
| HTTPS / real CORS | Reverse proxy + scoped origins (currently `*` on MinIO for demo) |
| S3 server-side encryption | `SSE-S3` / `SSE-KMS` headers on init |

## Troubleshooting

- **`Access denied` running `mvnw.cmd`** — Windows blocked it. Run `Unblock-File mvnw.cmd` in PowerShell, or use the system Maven directly.
- **S3 mode says `ETag missing — CORS expose-headers misconfigured`** — bucket CORS isn't exposing `ETag`. The Spring bootstrap should set this on startup; check Spring logs for `CORS policy applied to bucket`.
- **S3 init returns 500 with `Unable to execute HTTP request`** — MinIO isn't running. `docker compose ps` to check.
- **S3 init fails with `EntityTooSmall`** — you picked a chunk size < 5 MB. The UI should prevent this but if you bypass it, S3 will reject any non-final part smaller than 5 MB.
- **Local downloads stop at 2 GB** — browser `Blob` cap. Use the File System Access API (`showSaveFilePicker`) and stream-write instead.
- **Server restart loses local sessions** — yes, by design. The local registry is in-memory.
