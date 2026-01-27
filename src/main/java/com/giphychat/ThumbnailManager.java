package com.giphychat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import javax.imageio.ImageIO;
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
    // HttpClient gets its own internal thread pool to avoid deadlock:
    // if we shared 'executor', all 4 threads could block on send() while
    // HttpClient needs threads from the same pool for I/O → timeout.
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private final MediaCache cache;
    private final Map<String, ResourceLocation> textures = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public ThumbnailManager(MediaCache cache) {
        this.cache = cache;
    }

    /**
     * Request an animated GIF for the given URL. Downloads the GIF bytes
     * (or reads from cache), extracts frames on a background thread, then
     * registers textures on the render thread and invokes the callback.
     */
    public void requestAnimatedGif(String url, Consumer<AnimatedGif> callback) {
        if (failed.contains(url)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            Optional<byte[]> cached = cache.get(url);
            byte[] bytes = cached.orElseGet(() -> download(url));
            if (bytes == null || isWebP(bytes)) {
                return null;
            }
            return bytes;
        }, executor).thenAcceptAsync(bytes -> {
            if (bytes == null) {
                return;
            }
            // Must register textures on the render thread
            Minecraft.getInstance().execute(() -> {
                AnimatedGif anim = AnimatedGif.fromGifBytes(bytes, GiphyChatMod.MOD_ID + "/anim/" + url.hashCode());
                if (anim != null) {
                    callback.accept(anim);
                }
            });
        }, executor);
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
                LOGGER.warn("Thumbnail is WebP (unsupported): {}", url);
                return null;
            }
            if (bytes.length < 4) {
                LOGGER.warn("Thumbnail data too small ({} bytes) for {}", bytes.length, url);
                return null;
            }
            // NativeImage.read() only accepts PNG. Convert GIF/JPEG to PNG first.
            if (!isPng(bytes)) {
                bytes = convertToPng(bytes);
                if (bytes == null) {
                    LOGGER.warn("Failed to convert thumbnail to PNG for {}", url);
                    return null;
                }
            }
            return NativeImage.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            LOGGER.warn("Failed to decode thumbnail image from {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** Convert any ImageIO-supported format (GIF, JPEG, BMP) to PNG bytes. */
    private byte[] convertToPng(byte[] imageBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) {
                LOGGER.warn("ImageIO could not read image ({} bytes)", imageBytes.length);
                return null;
            }
            // Ensure ARGB so transparency is preserved
            BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(img, 0, 0, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(argb, "PNG", out);
            return out.toByteArray();
        } catch (IOException e) {
            LOGGER.warn("PNG conversion failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isPng(byte[] bytes) {
        // PNG signature: 0x89 P N G
        return bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    private byte[] download(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "image/gif, image/png, image/jpeg")
                .header("User-Agent", "GiphyChat/1.0")
                .GET()
                .build();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
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
            } catch (IOException e) {
                LOGGER.warn("Thumbnail download attempt {}/2 failed for {}: {}", attempt, url, e.getMessage());
                if (attempt < 2) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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
