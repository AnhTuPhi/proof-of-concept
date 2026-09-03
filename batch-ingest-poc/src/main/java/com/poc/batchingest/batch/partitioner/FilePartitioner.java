package com.poc.batchingest.batch.partitioner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Splits a directory of files into N partitions, one per worker step, by handing each worker
 * a comma-separated list of file URIs to process. Files are distributed round-robin so a
 * directory of mixed-size files spreads work reasonably evenly.
 *
 * <p>The grid size requested by the manager step is a hint — if there are fewer files than
 * partitions we collapse to one partition per file.
 */
@Slf4j
public class FilePartitioner implements Partitioner {

    private final String inputDir;
    private final String globPattern;

    public FilePartitioner(String inputDir, String globPattern) {
        this.inputDir = inputDir;
        this.globPattern = globPattern;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        List<Resource> files = listMatchingFiles();
        if (files.isEmpty()) {
            log.warn("FilePartitioner found no files in {} matching {}", inputDir, globPattern);
            return Map.of();
        }

        int effectiveGrid = Math.min(gridSize, files.size());
        Map<String, ExecutionContext> partitions = new HashMap<>(effectiveGrid);
        for (int i = 0; i < effectiveGrid; i++) {
            partitions.put("partition-" + i, new ExecutionContext());
        }

        for (int i = 0; i < files.size(); i++) {
            String key = "partition-" + (i % effectiveGrid);
            ExecutionContext ctx = partitions.get(key);
            String existing = ctx.containsKey("files") ? ctx.getString("files") : "";
            String uri = toUri(files.get(i));
            ctx.putString("files", existing.isEmpty() ? uri : existing + "," + uri);
            ctx.putString("partitionId", key);
            ctx.putString("runId", UUID.randomUUID().toString());
        }

        log.info("FilePartitioner produced {} partition(s) across {} file(s) from {}",
                partitions.size(), files.size(), inputDir);
        return partitions;
    }

    private List<Resource> listMatchingFiles() {
        try {
            String location = "file:" + normalizeDir(inputDir) + "/" + globPattern;
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
            return Stream.of(resources)
                    .filter(Resource::isReadable)
                    .sorted((a, b) -> a.getFilename().compareTo(b.getFilename()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not enumerate files in " + inputDir, e);
        }
    }

    private static String normalizeDir(String dir) {
        return dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
    }

    private static String toUri(Resource resource) {
        if (resource instanceof FileSystemResource fr) {
            return fr.getFile().toURI().toString();
        }
        try {
            return resource.getURI().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot derive URI for " + resource, e);
        }
    }
}
