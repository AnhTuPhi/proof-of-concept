package com.poc.upload.service;

import com.poc.upload.config.S3Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

@Service
public class S3UploadService {

    private static final Logger log = LoggerFactory.getLogger(S3UploadService.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Config cfg;

    public S3UploadService(S3Client s3, S3Presigner presigner, S3Config cfg) {
        this.s3 = s3;
        this.presigner = presigner;
        this.cfg = cfg;
    }

    public record InitResult(String uploadId, String key, int partCount, int chunkSize) {}

    public record PartRef(int partNumber, String etag) {}

    public record FileInfo(String key, String filename, long size, String lastModified, String etag) {}

    public InitResult init(String filename, String contentType, long totalSize, int chunkSize) {
        if (totalSize <= 0) throw new IllegalArgumentException("totalSize must be > 0");
        if (chunkSize < 5 * 1024 * 1024) {
            throw new IllegalArgumentException("S3 multipart minimum part size is 5 MB (got " + chunkSize + ")");
        }
        String key = "uploads/" + UUID.randomUUID() + "/" + sanitize(filename);
        CreateMultipartUploadResponse r = s3.createMultipartUpload(b -> b
                .bucket(cfg.bucket())
                .key(key)
                .contentType(contentType));
        int partCount = (int) Math.ceil((double) totalSize / chunkSize);
        log.info("S3 multipart init uploadId={} key={} parts={}", r.uploadId(), key, partCount);
        return new InitResult(r.uploadId(), key, partCount, chunkSize);
    }

    public List<String> signPartUrls(String uploadId, String key, int partCount) {
        return IntStream.rangeClosed(1, partCount)
                .mapToObj(i -> {
                    UploadPartRequest req = UploadPartRequest.builder()
                            .bucket(cfg.bucket())
                            .key(key)
                            .uploadId(uploadId)
                            .partNumber(i)
                            .build();
                    return presigner.presignUploadPart(UploadPartPresignRequest.builder()
                                    .signatureDuration(Duration.ofHours(1))
                                    .uploadPartRequest(req)
                                    .build())
                            .url()
                            .toString();
                })
                .toList();
    }

    public String complete(String uploadId, String key, List<PartRef> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .sorted(Comparator.comparingInt(PartRef::partNumber))
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.etag())
                        .build())
                .toList();

        CompleteMultipartUploadResponse r = s3.completeMultipartUpload(b -> b
                .bucket(cfg.bucket())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(m -> m.parts(completedParts)));
        log.info("S3 multipart complete key={} etag={}", key, r.eTag());
        return r.eTag();
    }

    public void abort(String uploadId, String key) {
        s3.abortMultipartUpload(b -> b
                .bucket(cfg.bucket())
                .key(key)
                .uploadId(uploadId));
        log.info("S3 multipart aborted uploadId={} key={}", uploadId, key);
    }

    public List<FileInfo> listFiles() {
        ListObjectsV2Response resp = s3.listObjectsV2(b -> b
                .bucket(cfg.bucket())
                .prefix("uploads/"));
        List<FileInfo> list = new ArrayList<>();
        for (S3Object o : resp.contents()) {
            list.add(new FileInfo(
                    o.key(),
                    extractFilename(o.key()),
                    o.size(),
                    o.lastModified().toString(),
                    o.eTag() == null ? "" : o.eTag().replace("\"", "")
            ));
        }
        return list;
    }

    public String signGetUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofHours(1))
                        .getObjectRequest(g -> g.bucket(cfg.bucket()).key(key))
                        .build())
                .url()
                .toString();
    }

    private static String sanitize(String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String extractFilename(String key) {
        int i = key.lastIndexOf('/');
        return i < 0 ? key : key.substring(i + 1);
    }
}
