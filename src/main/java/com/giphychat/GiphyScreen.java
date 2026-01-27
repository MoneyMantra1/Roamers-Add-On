package com.giphychat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class GiphyScreen extends Screen {
    private static final int THUMB_SIZE = 80;
    private static final int PADDING = 8;
    private static final int TOP_BAR_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 20;
    private static final int LIMIT = 24;

    private final GiphyApiClient apiClient = new GiphyApiClient();
    private final ThumbnailManager thumbnailManager;
    private final Map<String, ResourceLocation> thumbnails = new ConcurrentHashMap<>();

    private EditBox searchField;
    private Button searchButton;
    private Button trendingButton;

    private final List<GiphyResult> results = new ArrayList<>();
    private Component statusText = Component.translatable("screen.giphychat.status.ready");
    private Mode mode = Mode.SEARCH;
    private String currentQuery = "";
    private int offset = 0;
    private boolean hasMore = true;
    private boolean loading = false;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int requestToken = 0;
    private CompletableFuture<List<GiphyResult>> currentRequest;

    public GiphyScreen() {
        super(Component.translatable("screen.giphychat.title"));
        Path cacheDir = Minecraft.getInstance().gameDirectory.toPath().resolve("cache").resolve("giphychat");
        try {
            this.thumbnailManager = new ThumbnailManager(new MediaCache(cacheDir, Duration.ofDays(7), 250L * 1024L * 1024L));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create cache directory", e);
        }
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(240, width - 40);
        int fieldX = (width - fieldWidth) / 2;
        int fieldY = 12;
        searchField = new EditBox(font, fieldX, fieldY, fieldWidth, 20, Component.translatable("screen.giphychat.search"));
        searchField.setMaxLength(120);
        searchField.setValue(currentQuery);
        searchField.setFocused(true);
        addRenderableWidget(searchField);

        searchButton = Button.builder(Component.translatable("screen.giphychat.search"), button -> startSearch())
                .bounds(fieldX + fieldWidth + 8, fieldY, 70, 20)
                .build();
        addRenderableWidget(searchButton);

        trendingButton = Button.builder(Component.translatable("screen.giphychat.trending"), button -> startTrending())
                .bounds(searchButton.getX() + searchButton.getWidth() + 8, fieldY, 90, 20)
                .build();
        addRenderableWidget(trendingButton);
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        init();
    }

    @Override
    public void removed() {
        super.removed();
        if (currentRequest != null) {
            currentRequest.cancel(true);
        }
        apiClient.close();
        thumbnailManager.close();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            if (searchField.isFocused()) {
                startSearch();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int gridHeight = height - TOP_BAR_HEIGHT - FOOTER_HEIGHT - 20;
        if (gridHeight > 0) {
            scrollOffset -= (int) (scrollY * 20);
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        }
        if (shouldLoadMore()) {
            loadMore();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            GiphyResult clicked = findResultAt(mouseX, mouseY);
            if (clicked != null) {
                sendChat(clicked.gifUrl());
                Minecraft.getInstance().setScreen(null);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 4, 0xFFFFFF);

        int gridTop = TOP_BAR_HEIGHT + 10;
        int gridBottom = height - FOOTER_HEIGHT - 6;
        int gridHeight = gridBottom - gridTop;
        int columns = Math.max(1, (width - 40) / (THUMB_SIZE + PADDING));
        int gridWidth = columns * THUMB_SIZE + (columns - 1) * PADDING;
        int startX = (width - gridWidth) / 2;
        int rowHeight = THUMB_SIZE + PADDING;
        int totalRows = (int) Math.ceil(results.size() / (double) columns);
        maxScroll = Math.max(0, totalRows * rowHeight - gridHeight);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int startIndex = Math.max(0, scrollOffset / rowHeight * columns);
        int endIndex = Math.min(results.size(), startIndex + ((gridHeight / rowHeight) + 2) * columns);

        for (int i = startIndex; i < endIndex; i++) {
            GiphyResult result = results.get(i);
            int row = i / columns;
            int col = i % columns;
            int x = startX + col * (THUMB_SIZE + PADDING);
            int y = gridTop + row * rowHeight - scrollOffset;
            if (y + THUMB_SIZE < gridTop || y > gridBottom) {
                continue;
            }
            ResourceLocation texture = thumbnails.get(result.thumbnailUrl());
            if (texture == null) {
                thumbnailManager.requestThumbnail(result.thumbnailUrl(), location -> thumbnails.put(result.thumbnailUrl(), location));
                graphics.fill(x, y, x + THUMB_SIZE, y + THUMB_SIZE, 0xFF2B2B2B);
            } else {
                graphics.blit(texture, x, y, 0, 0, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE);
            }
        }

        graphics.drawString(font, statusText, 12, height - FOOTER_HEIGHT, 0xAAAAAA);
        Component powered = Component.translatable("screen.giphychat.powered");
        int poweredWidth = font.width(powered);
        graphics.drawString(font, powered, width - poweredWidth - 12, height - FOOTER_HEIGHT, 0xAAAAAA);
    }

    private void startSearch() {
        String query = searchField.getValue().trim();
        if (query.isEmpty()) {
            return;
        }
        mode = Mode.SEARCH;
        currentQuery = query;
        offset = 0;
        results.clear();
        scrollOffset = 0;
        hasMore = true;
        fetchResults();
    }

    private void startTrending() {
        mode = Mode.TRENDING;
        currentQuery = "";
        offset = 0;
        results.clear();
        scrollOffset = 0;
        hasMore = true;
        fetchResults();
    }

    private void loadMore() {
        if (!loading && hasMore) {
            offset += LIMIT;
            fetchResults();
        }
    }

    private void fetchResults() {
        if (loading) {
            return;
        }
        loading = true;
        statusText = Component.translatable("screen.giphychat.status.searching");
        int token = ++requestToken;
        if (currentRequest != null) {
            currentRequest.cancel(true);
        }
        if (mode == Mode.SEARCH) {
            currentRequest = apiClient.search(currentQuery, offset, LIMIT);
        } else {
            currentRequest = apiClient.trending(offset, LIMIT);
        }
        currentRequest.whenComplete((response, throwable) -> Minecraft.getInstance().execute(() -> {
            if (token != requestToken) {
                return;
            }
            loading = false;
            if (throwable != null) {
                statusText = determineStatus(throwable);
                return;
            }
            if (response != null) {
                results.addAll(response);
                if (response.size() < LIMIT) {
                    hasMore = false;
                }
            }
            if (results.isEmpty()) {
                statusText = Component.translatable("screen.giphychat.status.no_results");
            } else {
                statusText = Component.translatable("screen.giphychat.status.ready");
            }
        }));
    }

    private Component determineStatus(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
            return Component.translatable("screen.giphychat.status.offline");
        }
        return Component.translatable("screen.giphychat.status.error");
    }

    private boolean shouldLoadMore() {
        return hasMore && !loading && scrollOffset >= maxScroll - (THUMB_SIZE + PADDING);
    }

    private GiphyResult findResultAt(double mouseX, double mouseY) {
        int gridTop = TOP_BAR_HEIGHT + 10;
        int gridBottom = height - FOOTER_HEIGHT - 6;
        int gridHeight = gridBottom - gridTop;
        int columns = Math.max(1, (width - 40) / (THUMB_SIZE + PADDING));
        int gridWidth = columns * THUMB_SIZE + (columns - 1) * PADDING;
        int startX = (width - gridWidth) / 2;
        int rowHeight = THUMB_SIZE + PADDING;
        if (mouseY < gridTop || mouseY > gridBottom) {
            return null;
        }
        int adjustedY = (int) mouseY - gridTop + scrollOffset;
        int row = adjustedY / rowHeight;
        int col = ((int) mouseX - startX) / (THUMB_SIZE + PADDING);
        if (col < 0 || col >= columns) {
            return null;
        }
        int index = row * columns + col;
        if (index < 0 || index >= results.size()) {
            return null;
        }
        int cellX = startX + col * (THUMB_SIZE + PADDING);
        int cellY = gridTop + row * rowHeight - scrollOffset;
        if (mouseX < cellX || mouseX > cellX + THUMB_SIZE || mouseY < cellY || mouseY > cellY + THUMB_SIZE) {
            return null;
        }
        return results.get(index);
    }

    private void sendChat(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            return;
        }
        minecraft.player.connection.sendChat(message);
    }

    private enum Mode {
        SEARCH,
        TRENDING
    }
}
