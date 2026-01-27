# GiphyChat

GiphyChat is a client-only NeoForge 1.21.1 mod that lets you browse GIPHY in-game and send GIF URLs to chat.

## Features

- Press **G** to open the GIPHY browser.
- Search or view trending GIFs.
- Click a thumbnail to instantly send the GIF URL in chat.
- Media thumbnails are cached on disk to reduce API hits.

## Build

```bash
./gradlew build
```

The built jar is located at `build/libs/giphychat-<version>.jar`.

Note: the Gradle wrapper jar is stored as a Base64 file and is decoded automatically by `gradlew`/`gradlew.bat` on first run.

## Cache

Thumbnails are cached at:

```
.minecraft/cache/giphychat/
```

Delete this folder to clear the cache.

## Keybind

Change the keybind in **Controls → GiphyChat → Open GIPHY**.

## GIPHY API Key

The API key is embedded in `GiphyApiClient` as a private static final constant. Update `GIPHY_API_KEY` if you want to use your own key.
