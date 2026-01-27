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
    private static final String[] API_KEYS = {
            "Um3hPmOOjRNXIrAnI9dKoN2X1ojMROrDvInuguUA4iqOBbz25AMkiqt29HoBicJD",
            "z29biDxAhVzVYoRDeKrGFBkvLyaf9p1SwsAIflx3Zy8I93LuIkvliuuinhfJuUIf",
            "hBYvLSSirsv9wLEN0TIfhwzwQWrTDsDSxD0hh6OSaqWJDMD8wK7n0oOBWLSTMfY6",
            "XN9HrdlJlicWQ7sfJHPB7XIMwgNmmG10PtKn0UwkHCIclrhUDAxCTs8NYr2xqsxA",
            "LnVFHtnugGFMzkvPhBID2pc7zMZZDwIb6GRzPa7Q2uVW15Gaap9BBp7g5oGlgEAV"
    };
    private static final String CLIENT_KEY = "giphychat-mod";
    // Minimum interval between API requests (~100 req/min per key)
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
    private volatile int currentKeyIndex = 0;

    public record SearchResponse(List<GiphyResult> results, String next) {}

    public CompletableFuture<SearchResponse> search(String query, String pos, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String baseUrl = API_BASE + "/search?q=" + encoded
                + "&client_key=" + CLIENT_KEY + "&limit=" + limit
                + "&media_filter=tinygif,gif"
                + (pos != null && !pos.isEmpty() ? "&pos=" + pos : "");
        return fetch(baseUrl);
    }

    public CompletableFuture<SearchResponse> featured(String pos, int limit) {
        String baseUrl = API_BASE + "/featured?"
                + "client_key=" + CLIENT_KEY + "&limit=" + limit
                + "&media_filter=tinygif,gif"
                + (pos != null && !pos.isEmpty() ? "&pos=" + pos : "");
        return fetch(baseUrl);
    }

    /**
     * Sends the request with the current API key. On 403/429 (quota exhausted),
     * rotates to the next key and retries, cycling through all keys once.
     */
    private CompletableFuture<SearchResponse> fetch(String baseUrl) {
        return CompletableFuture.supplyAsync(() -> {
            // Throttle to stay within 100 req/min per key
            long now = System.currentTimeMillis();
            long wait = lastRequestTimeMs + MIN_REQUEST_INTERVAL_MS - now;
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTimeMs = System.currentTimeMillis();

            int startIndex = currentKeyIndex;
            for (int attempt = 0; attempt < API_KEYS.length; attempt++) {
                int keyIndex = (startIndex + attempt) % API_KEYS.length;
                String url = baseUrl + "&key=" + API_KEYS[keyIndex];

                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    int status = response.statusCode();

                    if (status == 200) {
                        currentKeyIndex = keyIndex;
                        return parseResponse(response.body());
                    }

                    if (status == 403 || status == 429) {
                        LOGGER.warn("KLIPY key {} hit quota (HTTP {}), rotating to next key",
                                keyIndex + 1, status);
                        currentKeyIndex = (keyIndex + 1) % API_KEYS.length;
                        continue;
                    }

                    // Other errors are not key-related, don't retry
                    LOGGER.warn("KLIPY API returned HTTP {}", status);
                    throw new RuntimeException("KLIPY API error: HTTP " + status);
                } catch (IOException e) {
                    throw new RuntimeException("KLIPY API request failed", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("KLIPY API request interrupted", e);
                }
            }
            LOGGER.warn("All {} KLIPY API keys exhausted, cycling back to key 1", API_KEYS.length);
            throw new RuntimeException("All KLIPY API keys quota exhausted \u2013 try again shortly");
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
