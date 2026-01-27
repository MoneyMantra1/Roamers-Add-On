package com.giphychat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public class MediaCache {
    private final Path cacheDir;
    private final long ttlMillis;
    private final long maxBytes;

    public MediaCache(Path cacheDir, Duration ttl, long maxBytes) throws IOException {
        this.cacheDir = cacheDir;
        this.ttlMillis = ttl.toMillis();
        this.maxBytes = maxBytes;
        Files.createDirectories(cacheDir);
    }

    public Optional<byte[]> get(String url) {
        Path path = pathForUrl(url);
        try {
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis();
            if (age > ttlMillis) {
                Files.deleteIfExists(path);
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(path);
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return Optional.of(bytes);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void evict(String url) {
        Path path = pathForUrl(url);
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public void put(String url, byte[] bytes) {
        Path path = pathForUrl(url);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            prune();
        } catch (IOException ignored) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignoredDelete) {
                // ignore
            }
        }
    }

    private void prune() throws IOException {
        List<Path> files = new ArrayList<>();
        long totalSize = 0L;
        try (var stream = Files.list(cacheDir)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                    totalSize += Files.size(path);
                }
            }
        }
        if (totalSize <= maxBytes) {
            return;
        }
        files.sort(Comparator.comparingLong(path -> {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));
        for (Path path : files) {
            if (totalSize <= maxBytes) {
                break;
            }
            long size = Files.size(path);
            Files.deleteIfExists(path);
            totalSize -= size;
        }
    }

    private Path pathForUrl(String url) {
        String hash = sha1(url);
        return cacheDir.resolve(hash + ".bin");
    }

    private String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
