package com.giphychat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GiphyApiClient implements AutoCloseable {
    private static final String API_BASE = "https://api.giphy.com/v1/gifs";
    private static final String GIPHY_API_KEY = "LJtnaehSNSU08kmDSQTlrC0IzU3wnLhi";

    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "GiphyChat-Api");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .build();

    public CompletableFuture<List<GiphyResult>> search(String query, int offset, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = API_BASE + "/search?api_key=" + GIPHY_API_KEY + "&q=" + encoded
                + "&limit=" + limit + "&offset=" + offset;
        return fetch(url);
    }

    public CompletableFuture<List<GiphyResult>> trending(int offset, int limit) {
        String url = API_BASE + "/trending?api_key=" + GIPHY_API_KEY + "&limit=" + limit + "&offset=" + offset;
        return fetch(url);
    }

    private CompletableFuture<List<GiphyResult>> fetch(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("GIPHY API error: " + response.statusCode());
                    }
                    return parseResults(response.body());
                }, executor);
    }

    private List<GiphyResult> parseResults(String body) {
        List<GiphyResult> results = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        if (data == null) {
            return results;
        }
        for (JsonElement element : data) {
            JsonObject obj = element.getAsJsonObject();
            String id = getString(obj, "id");
            String title = getString(obj, "title");
            JsonObject images = obj.getAsJsonObject("images");
            if (images == null) {
                continue;
            }
            String thumbnailUrl = getNestedString(images, "fixed_height_still", "url");
            String gifUrl = getNestedString(images, "fixed_height", "url");
            if (gifUrl == null || gifUrl.isBlank()) {
                gifUrl = getNestedString(images, "original", "url");
            }
            if (thumbnailUrl == null || gifUrl == null) {
                continue;
            }
            results.add(new GiphyResult(id, title, thumbnailUrl, gifUrl));
        }
        return results;
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    private String getNestedString(JsonObject obj, String child, String key) {
        if (obj == null || !obj.has(child)) {
            return null;
        }
        JsonObject childObj = obj.getAsJsonObject(child);
        if (childObj == null || !childObj.has(key)) {
            return null;
        }
        return childObj.get(key).getAsString();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
