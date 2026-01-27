package com.giphychat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
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
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API_BASE = "https://api.klipy.com/v2";
    private static final String API_KEY = "Um3hPmOOjRNXIrAnI9dKoN2X1ojMROrDvInuguUA4iqOBbz25AMkiqt29HoBicJD";
    private static final String CLIENT_KEY = "giphychat-mod";
    // Minimum interval between API requests (~100 req/min)
    private static final long MIN_REQUEST_INTERVAL_MS = 650;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "GiphyChat-Api");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(executor)
            .build();
    private volatile long lastRequestTimeMs;

    public record SearchResponse(List<GiphyResult> results, String next) {}

    public CompletableFuture<SearchResponse> search(String query, String pos, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = API_BASE + "/search?q=" + encoded + "&key=" + API_KEY
                + "&client_key=" + CLIENT_KEY + "&limit=" + limit
                + "&media_filter=tinygif,gif"
                + (pos != null && !pos.isEmpty() ? "&pos=" + pos : "");
        return fetch(url);
    }

    public CompletableFuture<SearchResponse> featured(String pos, int limit) {
        String url = API_BASE + "/featured?key=" + API_KEY
                + "&client_key=" + CLIENT_KEY + "&limit=" + limit
                + "&media_filter=tinygif,gif"
                + (pos != null && !pos.isEmpty() ? "&pos=" + pos : "");
        return fetch(url);
    }

    private CompletableFuture<SearchResponse> fetch(String url) {
        return CompletableFuture.supplyAsync(() -> {
            // Throttle to stay within 100 req/min
            long now = System.currentTimeMillis();
            long wait = lastRequestTimeMs + MIN_REQUEST_INTERVAL_MS - now;
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTimeMs = System.currentTimeMillis();

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOGGER.warn("KLIPY API returned HTTP {}", response.statusCode());
                    throw new RuntimeException("KLIPY API error: HTTP " + response.statusCode());
                }
                return parseResponse(response.body());
            } catch (IOException e) {
                throw new RuntimeException("KLIPY API request failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("KLIPY API request interrupted", e);
            }
        }, executor);
    }

    private SearchResponse parseResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String next = root.has("next") ? root.get("next").getAsString() : "";
        List<GiphyResult> results = new ArrayList<>();
        JsonArray resultsArray = root.getAsJsonArray("results");
        if (resultsArray == null) {
            return new SearchResponse(results, next);
        }
        for (JsonElement element : resultsArray) {
            JsonObject obj = element.getAsJsonObject();
            String id = getString(obj, "id");
            String title = getString(obj, "content_description");
            if (title.isEmpty()) {
                title = getString(obj, "title");
            }
            JsonObject mediaFormats = obj.getAsJsonObject("media_formats");
            if (mediaFormats == null) {
                continue;
            }
            String thumbnailUrl = getMediaUrl(mediaFormats, "tinygif");
            String gifUrl = getMediaUrl(mediaFormats, "gif");
            if (gifUrl == null) {
                gifUrl = getMediaUrl(mediaFormats, "mediumgif");
            }
            if (gifUrl == null) {
                gifUrl = getMediaUrl(mediaFormats, "tinygif");
            }
            if (thumbnailUrl == null || gifUrl == null) {
                continue;
            }
            results.add(new GiphyResult(id, title, thumbnailUrl, gifUrl));
        }
        return new SearchResponse(results, next);
    }

    private String getMediaUrl(JsonObject mediaFormats, String formatKey) {
        if (!mediaFormats.has(formatKey)) {
            return null;
        }
        JsonObject format = mediaFormats.getAsJsonObject(formatKey);
        if (format == null || !format.has("url")) {
            return null;
        }
        return format.get("url").getAsString();
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
