package com.poc.upload.service;

import com.poc.upload.model.StoredFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FileStorageService {

    private final Path filesRoot;
    private final Map<String, StoredFile> registry = new ConcurrentHashMap<>();

    public FileStorageService(@Value("${poc.storage-dir}") String storageDir) throws IOException {
        this.filesRoot = Path.of(storageDir, "files");
        Files.createDirectories(this.filesRoot);
    }

    public Path allocate(String fileId) throws IOException {
        Path dir = filesRoot.resolve(fileId);
        Files.createDirectories(dir);
        return dir.resolve("data.bin");
    }

    public void register(StoredFile sf) {
        registry.put(sf.fileId(), sf);
    }

    public StoredFile get(String fileId) {
        StoredFile sf = registry.get(fileId);
        if (sf == null) throw new IllegalArgumentException("Unknown fileId: " + fileId);
        return sf;
    }

    public Path path(String fileId) {
        return filesRoot.resolve(fileId).resolve("data.bin");
    }

    public Collection<StoredFile> listAll() {
        return registry.values();
    }
}
