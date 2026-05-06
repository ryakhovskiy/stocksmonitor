package org.kr.stocksmonitor.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.kr.stocksmonitor.utils.LogUtils;
import org.kr.stocksmonitor.yahoo.QuoteItem;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConfigManager {

    private static final Logger logger = LogManager.getLogger(ConfigManager.class);
    private static final Path FAVORITE_QUOTES_PATH = Paths.get(System.getProperty("user.dir"), ".favquotes.json");

    private static final ConfigManager instance = new ConfigManager();

    public static ConfigManager getInstance() {
        return instance;
    }

    private final Object favoritesLock = new Object();

    private ConfigManager() {
    }

    public List<QuoteItem> readFavoriteQuotes() {
        final Instant start = Instant.now();
        synchronized (favoritesLock) {
            try {
                if (!Files.exists(FAVORITE_QUOTES_PATH)) return Collections.emptyList();
                final String jsonContent = Files.readString(FAVORITE_QUOTES_PATH);
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
            try (FileWriter fileWriter = new FileWriter(FAVORITE_QUOTES_PATH.toFile())) {
                fileWriter.write(jsonArray.toString());
                logger.debug("Favorite yahoo quotes saved at: {}", FAVORITE_QUOTES_PATH);
            } catch (IOException e) {
                logger.error("Cannot save favorite yahoo quotes", e);
            }
        }
        LogUtils.debugDuration(logger, start, "saving the favorite yahoo quotes into the config");
    }

    void deleteFavoriteQuotes(List<QuoteItem> quotes) {
        final List<QuoteItem> fileQuotes = new ArrayList<>(readFavoriteQuotes());
        fileQuotes.removeAll(quotes);
        saveFavoriteQuotes(fileQuotes);
    }
}
