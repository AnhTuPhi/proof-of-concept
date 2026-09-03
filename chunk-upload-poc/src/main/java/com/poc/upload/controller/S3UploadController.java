package com.poc.upload.controller;

import com.poc.upload.service.S3UploadService;
import com.poc.upload.service.S3UploadService.FileInfo;
import com.poc.upload.service.S3UploadService.InitResult;
import com.poc.upload.service.S3UploadService.PartRef;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/s3")
public class S3UploadController {

    private final S3UploadService service;

    public S3UploadController(S3UploadService service) {
        this.service = service;
    }

    public record InitRequest(String filename, String contentType, long totalSize, int chunkSize) {}
    public record SignPartsRequest(String uploadId, String key, int partCount) {}
    public record CompleteRequest(String uploadId, String key, List<PartRef> parts) {}
    public record AbortRequest(String uploadId, String key) {}
    public record CompleteResponse(String key, String etag) {}

    @PostMapping("/uploads/init")
    public InitResult init(@RequestBody InitRequest req) {
        return service.init(
                req.filename(),
                req.contentType() == null ? "application/octet-stream" : req.contentType(),
                req.totalSize(),
                req.chunkSize());
    }

    @PostMapping("/uploads/sign-parts")
    public List<String> signParts(@RequestBody SignPartsRequest req) {
        return service.signPartUrls(req.uploadId(), req.key(), req.partCount());
    }

    @PostMapping("/uploads/complete")
    public CompleteResponse complete(@RequestBody CompleteRequest req) {
        String etag = service.complete(req.uploadId(), req.key(), req.parts());
        return new CompleteResponse(req.key(), etag);
    }

    @PostMapping("/uploads/abort")
    public void abort(@RequestBody AbortRequest req) {
        service.abort(req.uploadId(), req.key());
    }

    @GetMapping("/files")
    public List<FileInfo> listFiles() {
        return service.listFiles();
    }

    @GetMapping("/files/signed-get")
    public Map<String, String> signGet(@RequestParam String key) {
        return Map.of("url", service.signGetUrl(key));
    }
}
