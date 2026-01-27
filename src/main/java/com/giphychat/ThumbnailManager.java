package com.giphychat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ThumbnailManager implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "GiphyChat-Thumbnails");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .executor(executor)
            .build();
    private final MediaCache cache;
    private final Map<String, ResourceLocation> textures = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public ThumbnailManager(MediaCache cache) {
        this.cache = cache;
    }

    public void requestThumbnail(String url, Consumer<ResourceLocation> callback) {
        ResourceLocation existing = textures.get(url);
        if (existing != null) {
            callback.accept(existing);
            return;
        }
        if (failed.contains(url) || !inFlight.add(url)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> loadImage(url), executor)
                .thenAcceptAsync(image -> {
                    if (image == null) {
                        failed.add(url);
                        inFlight.remove(url);
                        return;
                    }
                    Minecraft.getInstance().execute(() -> {
                        DynamicTexture texture = new DynamicTexture(image);
                        ResourceLocation location = Minecraft.getInstance().getTextureManager()
                                .register(GiphyChatMod.MOD_ID + "/thumb/" + url.hashCode(), texture);
                        textures.put(url, location);
                        inFlight.remove(url);
                        callback.accept(location);
                    });
                }, executor)
                .exceptionally(error -> {
                    LOGGER.warn("Thumbnail request failed for {}: {}", url, error.getMessage());
                    failed.add(url);
                    inFlight.remove(url);
                    return null;
                });
    }

    private NativeImage loadImage(String url) {
        try {
            Optional<byte[]> cached = cache.get(url);
            byte[] bytes;
            if (cached.isPresent()) {
                bytes = cached.get();
                if (isWebP(bytes)) {
                    LOGGER.warn("Cached thumbnail is WebP, re-downloading: {}", url);
                    cache.evict(url);
                    bytes = download(url);
                }
            } else {
                bytes = download(url);
            }
            if (bytes == null) {
                LOGGER.warn("Thumbnail download returned no data for {}", url);
                return null;
            }
            if (isWebP(bytes)) {
                LOGGER.warn("Thumbnail is WebP (unsupported by NativeImage): {}", url);
                return null;
            }
            if (bytes.length < 4) {
                LOGGER.warn("Thumbnail data too small ({} bytes) for {}", bytes.length, url);
                return null;
            }
            LOGGER.debug("Decoding thumbnail: {} bytes, sig={}{}{}{} for {}", bytes.length,
                    (char) (bytes[0] & 0xFF), (char) (bytes[1] & 0xFF),
                    (char) (bytes[2] & 0xFF), (char) (bytes[3] & 0xFF), url);
            return NativeImage.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            LOGGER.warn("Failed to decode thumbnail image from {}: {}", url, e.getMessage());
            return null;
        }
    }

    private byte[] download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "image/gif, image/png, image/jpeg")
                    .header("User-Agent", "GiphyChat/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("unknown");
            LOGGER.debug("Thumbnail HTTP {} Content-Type={} size={} for {}",
                    response.statusCode(), contentType, response.body() != null ? response.body().length : 0, url);
            if (response.statusCode() != 200) {
                LOGGER.warn("Thumbnail download returned HTTP {} (Content-Type={}) for {}", response.statusCode(), contentType, url);
                return null;
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) {
                LOGGER.warn("Thumbnail download returned empty body for {}", url);
                return null;
            }
            cache.put(url, bytes);
            return bytes;
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("Thumbnail download failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** WebP files start with RIFF....WEBP */
    private static boolean isWebP(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    @Override
    public void close() {
        executor.shutdownNow();
        Minecraft minecraft = Minecraft.getInstance();
        textures.values().forEach(location -> minecraft.getTextureManager().release(location));
        textures.clear();
    }
}
