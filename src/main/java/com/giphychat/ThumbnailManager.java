package com.giphychat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

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
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "GiphyChat-Thumbnails");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .build();
    private final MediaCache cache;
    private final Map<String, ResourceLocation> textures = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ThumbnailManager(MediaCache cache) {
        this.cache = cache;
    }

    public void requestThumbnail(String url, Consumer<ResourceLocation> callback) {
        ResourceLocation existing = textures.get(url);
        if (existing != null) {
            callback.accept(existing);
            return;
        }
        if (!inFlight.add(url)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> loadImage(url), executor)
                .thenAcceptAsync(image -> {
                    if (image == null) {
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
                    inFlight.remove(url);
                    return null;
                });
    }

    private NativeImage loadImage(String url) {
        try {
            Optional<byte[]> cached = cache.get(url);
            byte[] bytes = cached.orElseGet(() -> download(url));
            if (bytes == null) {
                return null;
            }
            return NativeImage.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            byte[] bytes = response.body();
            if (bytes != null && bytes.length > 0) {
                cache.put(url, bytes);
            }
            return bytes;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        Minecraft minecraft = Minecraft.getInstance();
        textures.values().forEach(location -> minecraft.getTextureManager().release(location));
        textures.clear();
    }
}
