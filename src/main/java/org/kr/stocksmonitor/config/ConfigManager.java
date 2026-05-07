package org.kr.stocksmonitor.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kr.stocksmonitor.utils.LogUtils;
import org.kr.stocksmonitor.yahoo.QuoteItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConfigManager {

    private static final Logger logger = LogManager.getLogger(ConfigManager.class);
    // App-private config dir under the user's home — same on every launch regardless of CWD.
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".stocksmonitor");
    private static final Path FAVORITE_QUOTES_PATH = CONFIG_DIR.resolve("favquotes.json");
    private static final Path SETTINGS_PATH = CONFIG_DIR.resolve("settings.json");
    // Old location used before the migration to user.home — read-only fallback / one-shot import.
    private static final Path LEGACY_FAVORITE_QUOTES_PATH = Paths.get(System.getProperty("user.dir"), ".favquotes.json");

    private static final ConfigManager instance = new ConfigManager();

    public static ConfigManager getInstance() {
        return instance;
    }

    private final Object favoritesLock = new Object();
    private final Object settingsLock = new Object();

    private ConfigManager() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            logger.error("Cannot create config dir {}", CONFIG_DIR, e);
        }
        migrateLegacyFavorites();
    }

    private void migrateLegacyFavorites() {
        if (Files.exists(FAVORITE_QUOTES_PATH)) return;
        if (!Files.exists(LEGACY_FAVORITE_QUOTES_PATH)) return;
        try {
            Files.copy(LEGACY_FAVORITE_QUOTES_PATH, FAVORITE_QUOTES_PATH);
            logger.info("Migrated favorites from {} to {}", LEGACY_FAVORITE_QUOTES_PATH, FAVORITE_QUOTES_PATH);
        } catch (IOException e) {
            logger.warn("Could not migrate legacy favorites file", e);
        }
    }

    public List<QuoteItem> readFavoriteQuotes() {
        final Instant start = Instant.now();
        synchronized (favoritesLock) {
            try {
                Path source = Files.exists(FAVORITE_QUOTES_PATH) ? FAVORITE_QUOTES_PATH
                        : (Files.exists(LEGACY_FAVORITE_QUOTES_PATH) ? LEGACY_FAVORITE_QUOTES_PATH : null);
                if (source == null) return Collections.emptyList();
                final String jsonContent = Files.readString(source);
                if (jsonContent.isEmpty()) return Collections.emptyList();
                JSONArray jsonArray = new JSONArray(jsonContent);
                List<QuoteItem> quotes = new ArrayList<>(jsonArray.length());
                for (int i = 0; i < jsonArray.length(); i++) {
                    quotes.add(new QuoteItem(jsonArray.getJSONObject(i)));
                }
                return quotes;
            } catch (IOException e) {
                logger.error("Error while reading favorite yahoo quotes", e);
                return Collections.emptyList();
            } finally {
                LogUtils.debugDuration(logger, start, "reading the favorite yahoo quotes from the config");
            }
        }
    }

    public void saveFavoriteQuotes(List<QuoteItem> quotes) {
        if (null == quotes) return;
        final Instant start = Instant.now();
        logger.debug("saving favorite yahoo quotes to the config file, total symbols: {}", quotes.size());
        JSONArray jsonArray = new JSONArray();
        for (QuoteItem quote : quotes)
            jsonArray.put(quote.toJsonObject());
        synchronized (favoritesLock) {
            // Atomic write: stage to .tmp then move into place so a crash never leaves a half-written file.
            Path tmp = FAVORITE_QUOTES_PATH.resolveSibling(FAVORITE_QUOTES_PATH.getFileName() + ".tmp");
            try {
                Files.writeString(tmp, jsonArray.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, FAVORITE_QUOTES_PATH,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    // Some Windows file systems / cross-volume moves can't do atomic — fall back.
                    Files.move(tmp, FAVORITE_QUOTES_PATH, StandardCopyOption.REPLACE_EXISTING);
                }
                logger.debug("Favorite yahoo quotes saved at: {}", FAVORITE_QUOTES_PATH);
            } catch (IOException e) {
                logger.error("Cannot save favorite yahoo quotes", e);
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            }
        }
        LogUtils.debugDuration(logger, start, "saving the favorite yahoo quotes into the config");
    }

    void deleteFavoriteQuotes(List<QuoteItem> quotes) {
        final List<QuoteItem> fileQuotes = new ArrayList<>(readFavoriteQuotes());
        fileQuotes.removeAll(quotes);
        saveFavoriteQuotes(fileQuotes);
    }

    public String readLastSearchTerm() {
        synchronized (settingsLock) {
            try {
                if (!Files.exists(SETTINGS_PATH)) return "";
                String content = Files.readString(SETTINGS_PATH);
                if (content.isEmpty()) return "";
                return new JSONObject(content).optString("lastSearchTerm", "");
            } catch (IOException e) {
                logger.warn("Cannot read settings file", e);
                return "";
            }
        }
    }

    public void saveLastSearchTerm(String term) {
        if (term == null) return;
        synchronized (settingsLock) {
            JSONObject obj = new JSONObject();
            try {
                if (Files.exists(SETTINGS_PATH)) {
                    String existing = Files.readString(SETTINGS_PATH);
                    if (!existing.isEmpty()) obj = new JSONObject(existing);
                }
            } catch (IOException e) {
                logger.warn("Cannot read settings file before write — overwriting", e);
            }
            obj.put("lastSearchTerm", term);
            try {
                Files.writeString(SETTINGS_PATH, obj.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.error("Cannot write settings file", e);
            }
        }
    }
}
