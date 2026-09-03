package com.poc.upload.controller;

import com.poc.upload.model.StoredFile;
import com.poc.upload.model.UploadSession;
import com.poc.upload.service.UploadSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadSessionService service;

    public UploadController(UploadSessionService service) {
        this.service = service;
    }

    public record InitRequest(String filename, String contentType, long totalSize, int chunkSize) {}
    public record InitResponse(String uploadId, int totalChunks, int chunkSize) {}
    public record StatusResponse(String uploadId, int totalChunks, List<Integer> completedChunks, String status) {}
    public record CompleteResponse(String fileId, String filename, long size, String sha256) {}

    @PostMapping("/init")
    public InitResponse init(@RequestBody InitRequest req) throws IOException {
        UploadSession s = service.init(
                req.filename(),
                req.contentType() == null ? "application/octet-stream" : req.contentType(),
                req.totalSize(),
                req.chunkSize());
        return new InitResponse(s.uploadId, s.totalChunks, s.chunkSize);
    }

    @PutMapping("/{id}/chunks/{index}")
    public void uploadChunk(@PathVariable String id,
                            @PathVariable int index,
                            HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            service.writeChunk(id, index, in);
        }
    }

    @GetMapping("/{id}/status")
    public StatusResponse status(@PathVariable String id) {
        UploadSession s = service.get(id);
        return new StatusResponse(s.uploadId, s.totalChunks, new ArrayList<>(s.completedChunks), s.status);
    }

    @PostMapping("/{id}/complete")
    public CompleteResponse complete(@PathVariable String id) throws IOException, NoSuchAlgorithmException {
        StoredFile sf = service.complete(id);
        return new CompleteResponse(sf.fileId(), sf.filename(), sf.size(), sf.sha256());
    }
}
