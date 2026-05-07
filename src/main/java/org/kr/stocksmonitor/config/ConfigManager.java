package org.kr.stocksmonitor.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kr.stocksmonitor.model.Instrument;
import org.kr.stocksmonitor.utils.LogUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private static final Logger logger = LogManager.getLogger(ConfigManager.class);
    // App-private config dir under the user's home — same on every launch regardless of CWD.
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".stocksmonitor");
    private static final Path FAVORITE_QUOTES_PATH = CONFIG_DIR.resolve("favquotes.json");
    private static final Path SETTINGS_PATH = CONFIG_DIR.resolve("settings.json");
    // Old location used before the migration to user.home — read-only fallback / one-shot import.
    private static final Path LEGACY_FAVORITE_QUOTES_PATH = Paths.get(System.getProperty("user.dir"), ".favquotes.json");

    public static final String KEY_LAST_SEARCH_TERM = "lastSearchTerm";
    public static final String KEY_CURRENCY = "currency";
    public static final String KEY_START_DATE = "startDate";
    public static final String KEY_END_DATE = "endDate";
    public static final String KEY_SLIDER_VALUE = "sliderValue";
    public static final String KEY_LAST_TAB = "lastTab";
    public static final String KEY_SELECTED_SYMBOLS = "selectedSymbols";

    private static final ConfigManager instance = new ConfigManager();

    public static ConfigManager getInstance() {
        return instance;
    }

    private final Object favoritesLock = new Object();
    private final Object settingsLock = new Object();

    /**
     * Snapshot of persisted UI settings used to restore state at startup.
     * Any field may be null/-1 when not previously persisted; callers fall back to defaults.
     */
    public record AppSettings(String lastSearchTerm,
                              String currency,
                              LocalDate startDate,
                              LocalDate endDate,
                              int sliderValue,
                              String lastTab,
                              List<String> selectedSymbols) {}

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

    public List<Instrument> readFavoriteQuotes() {
        final Instant start = Instant.now();
        synchronized (favoritesLock) {
            try {
                Path source = Files.exists(FAVORITE_QUOTES_PATH) ? FAVORITE_QUOTES_PATH
                        : (Files.exists(LEGACY_FAVORITE_QUOTES_PATH) ? LEGACY_FAVORITE_QUOTES_PATH : null);
                if (source == null) return Collections.emptyList();
                final String jsonContent = Files.readString(source);
                if (jsonContent.isEmpty()) return Collections.emptyList();
                JSONArray jsonArray = new JSONArray(jsonContent);
                List<Instrument> quotes = new ArrayList<>(jsonArray.length());
                for (int i = 0; i < jsonArray.length(); i++) {
                    quotes.add(new Instrument(jsonArray.getJSONObject(i)));
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

    public void saveFavoriteQuotes(List<Instrument> quotes) {
        if (null == quotes) return;
        final Instant start = Instant.now();
        logger.debug("saving favorite yahoo quotes to the config file, total symbols: {}", quotes.size());
        JSONArray jsonArray = new JSONArray();
        for (Instrument quote : quotes)
            jsonArray.put(quote.toJsonObject());
        synchronized (favoritesLock) {
            atomicWriteString(FAVORITE_QUOTES_PATH, jsonArray.toString());
        }
        LogUtils.debugDuration(logger, start, "saving the favorite yahoo quotes into the config");
    }

    void deleteFavoriteQuotes(List<Instrument> quotes) {
        final List<Instrument> fileQuotes = new ArrayList<>(readFavoriteQuotes());
        fileQuotes.removeAll(quotes);
        saveFavoriteQuotes(fileQuotes);
    }

    public AppSettings readSettings() {
        synchronized (settingsLock) {
            JSONObject obj = readSettingsObject();
            JSONArray symArr = obj.optJSONArray(KEY_SELECTED_SYMBOLS);
            List<String> selectedSymbols;
            if (symArr == null) {
                selectedSymbols = List.of();
            } else {
                selectedSymbols = new ArrayList<>(symArr.length());
                for (int i = 0; i < symArr.length(); i++) {
                    String s = symArr.optString(i, "");
                    if (!s.isEmpty()) selectedSymbols.add(s);
                }
            }
            return new AppSettings(
                    obj.optString(KEY_LAST_SEARCH_TERM, ""),
                    obj.optString(KEY_CURRENCY, ""),
                    parseDate(obj.optString(KEY_START_DATE, "")),
                    parseDate(obj.optString(KEY_END_DATE, "")),
                    obj.optInt(KEY_SLIDER_VALUE, -1),
                    obj.optString(KEY_LAST_TAB, ""),
                    selectedSymbols
            );
        }
    }

    /** Read-modify-write a single setting. Cheap (settings.json is tiny), but callers should still debounce noisy sources. */
    public void saveSetting(String key, Object value) {
        if (key == null) return;
        synchronized (settingsLock) {
            JSONObject obj = readSettingsObject();
            if (value == null) obj.remove(key);
            else obj.put(key, value);
            writeSettingsObject(obj);
        }
    }

    /** Atomic write of multiple settings at once — preferable to N back-to-back saveSetting calls. */
    public void saveSettings(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        synchronized (settingsLock) {
            JSONObject obj = readSettingsObject();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                if (e.getValue() == null) obj.remove(e.getKey());
                else obj.put(e.getKey(), e.getValue());
            }
            writeSettingsObject(obj);
        }
    }

    public String readLastSearchTerm() {
        return readSettings().lastSearchTerm();
    }

    public void saveLastSearchTerm(String term) {
        if (term == null) return;
        saveSetting(KEY_LAST_SEARCH_TERM, term);
    }

    private JSONObject readSettingsObject() {
        try {
            if (!Files.exists(SETTINGS_PATH)) return new JSONObject();
            String content = Files.readString(SETTINGS_PATH);
            if (content.isEmpty()) return new JSONObject();
            return new JSONObject(content);
        } catch (IOException e) {
            logger.warn("Cannot read settings file", e);
            return new JSONObject();
        }
    }

    private void writeSettingsObject(JSONObject obj) {
        atomicWriteString(SETTINGS_PATH, obj.toString());
    }

    private void atomicWriteString(Path target, String content) {
        // Stage to .tmp then move into place so a crash never leaves a half-written file.
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some Windows file systems / cross-volume moves can't do atomic — fall back.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Cannot write {}", target, e);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return LocalDate.parse(s); }
        catch (DateTimeParseException e) { return null; }
    }
}
