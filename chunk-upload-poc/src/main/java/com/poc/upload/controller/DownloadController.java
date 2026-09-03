package com.poc.upload.controller;

import com.poc.upload.model.StoredFile;
import com.poc.upload.service.FileStorageService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

@RestController
@RequestMapping("/api/files")
public class DownloadController {

    private final FileStorageService storage;

    public DownloadController(FileStorageService storage) {
        this.storage = storage;
    }

    @GetMapping
    public Collection<StoredFile> list() {
        return storage.listAll();
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String id) {
        StoredFile sf = storage.get(id);
        return ResponseEntity.ok()
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", String.valueOf(sf.size()))
                .header("Content-Type", sf.contentType())
                .header("ETag", "\"" + sf.sha256() + "\"")
                .header("X-File-SHA256", sf.sha256())
                .header("X-Filename", sf.filename())
                .build();
    }

    @GetMapping("/{id}")
    public void download(@PathVariable String id,
                         @RequestHeader(value = "Range", required = false) String rangeHeader,
                         HttpServletResponse response) throws IOException {
        StoredFile sf = storage.get(id);
        Path path = storage.path(id);
        long fileSize = sf.size();

        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", "\"" + sf.sha256() + "\"");
        response.setHeader("X-File-SHA256", sf.sha256());
        response.setContentType(sf.contentType());
        response.setHeader("Access-Control-Expose-Headers",
                "Accept-Ranges, Content-Range, Content-Length, ETag, X-File-SHA256, X-Filename");

        if (rangeHeader == null || rangeHeader.isBlank()) {
            response.setStatus(HttpStatus.OK.value());
            response.setHeader("Content-Length", String.valueOf(fileSize));
            try (InputStream in = Files.newInputStream(path);
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
            return;
        }

        if (!rangeHeader.startsWith("bytes=")) {
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            return;
        }

        String spec = rangeHeader.substring(6);
        if (spec.contains(",")) {
            response.setStatus(HttpStatus.NOT_IMPLEMENTED.value());
            return;
        }

        String[] parts = spec.split("-", 2);
        long start, end;
        try {
            start = parts[0].isBlank() ? Math.max(0, fileSize - Long.parseLong(parts[1])) : Long.parseLong(parts[0]);
            end = parts.length > 1 && !parts[1].isBlank() && !parts[0].isBlank()
                    ? Long.parseLong(parts[1]) : fileSize - 1;
        } catch (NumberFormatException e) {
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            return;
        }

        if (start < 0 || end >= fileSize || start > end) {
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader("Content-Range", "bytes */" + fileSize);
            return;
        }

        long contentLength = end - start + 1;
        response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
        response.setHeader("Content-Length", String.valueOf(contentLength));

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             OutputStream out = response.getOutputStream()) {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = contentLength;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n == -1) break;
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
    }
}
