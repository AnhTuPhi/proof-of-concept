package com.poc.upload.service;

import com.poc.upload.model.StoredFile;
import com.poc.upload.model.UploadSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class UploadSessionService {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionService.class);

    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();
    private final Path tempRoot;
    private final FileStorageService storage;

    public UploadSessionService(@Value("${poc.storage-dir}") String storageDir,
                                FileStorageService storage) throws IOException {
        this.tempRoot = Path.of(storageDir, "temp");
        Files.createDirectories(this.tempRoot);
        this.storage = storage;
    }

    public UploadSession init(String filename, String contentType, long totalSize, int chunkSize) throws IOException {
        if (totalSize <= 0) throw new IllegalArgumentException("totalSize must be > 0");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0");

        String uploadId = UUID.randomUUID().toString();
        Path tempDir = tempRoot.resolve(uploadId);
        Files.createDirectories(tempDir);

        UploadSession s = new UploadSession(uploadId, sanitize(filename), contentType, totalSize, chunkSize, tempDir);
        sessions.put(uploadId, s);
        log.info("Init upload {} file={} size={} chunks={}", uploadId, filename, totalSize, s.totalChunks);
        return s;
    }

    public UploadSession get(String uploadId) {
        UploadSession s = sessions.get(uploadId);
        if (s == null) throw new IllegalArgumentException("Unknown uploadId: " + uploadId);
        return s;
    }

    public void writeChunk(String uploadId, int index, InputStream in) throws IOException {
        UploadSession s = get(uploadId);
        if (index < 0 || index >= s.totalChunks) {
            throw new IllegalArgumentException("Bad chunk index " + index + " for totalChunks=" + s.totalChunks);
        }
        Path chunkFile = s.tempDir.resolve(index + ".chunk");
        try (OutputStream out = Files.newOutputStream(chunkFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
        s.completedChunks.add(index);
        log.debug("Wrote chunk {} for {} ({}/{})", index, uploadId, s.completedChunks.size(), s.totalChunks);
    }

    public StoredFile complete(String uploadId) throws IOException, NoSuchAlgorithmException {
        UploadSession s = get(uploadId);
        if (s.completedChunks.size() != s.totalChunks) {
            throw new IllegalStateException("Missing chunks: have " + s.completedChunks.size()
                    + " expected " + s.totalChunks);
        }

        String fileId = UUID.randomUUID().toString();
        Path dest = storage.allocate(fileId);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long written = 0;

        try (OutputStream out = Files.newOutputStream(dest,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            for (int i = 0; i < s.totalChunks; i++) {
                Path chunk = s.tempDir.resolve(i + ".chunk");
                try (InputStream in = Files.newInputStream(chunk)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        sha.update(buf, 0, n);
                        written += n;
                    }
                }
            }
        }

        if (written != s.totalSize) {
            throw new IllegalStateException("Size mismatch: wrote=" + written + " expected=" + s.totalSize);
        }

        String hex = HexFormat.of().formatHex(sha.digest());
        StoredFile sf = new StoredFile(fileId, s.filename, s.contentType, written, hex);
        storage.register(sf);
        s.status = "DONE";
        s.fileId = fileId;

        deleteDir(s.tempDir);
        log.info("Completed upload {} -> fileId={} sha256={}", uploadId, fileId, hex);
        return sf;
    }

    private static String sanitize(String filename) {
        if (filename == null) return "unnamed";
        String name = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
