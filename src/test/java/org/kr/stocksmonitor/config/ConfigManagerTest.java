package org.kr.stocksmonitor.config;

import org.junit.jupiter.api.Test;
import org.kr.stocksmonitor.model.Instrument;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    @Test
    void testFavoriteQuotes() {
        final ConfigManager configManager = ConfigManager.getInstance();
        final List<Instrument> quotes = List.of(
                new Instrument("test", "ex.1", "test short", "ETF", "X1", "ind", "disp", "long", "exch", false),
                new Instrument("test", "ex2", "test short2", "ETF", "X2", "ind", "disp", "long", "exch", false),
                new Instrument("test", "ex3", "test short3", "ETF", "X3", "ind", "disp", "long", "exch", false),
                new Instrument("test", "ex4", "test short4", "ETF", "X4", "ind", "disp", "long", "exch", false)
        );

        configManager.saveFavoriteQuotes(quotes);
        List<Instrument> configQuotes = configManager.readFavoriteQuotes();
        assertFalse(configQuotes.isEmpty());
        assertTrue(quotes.containsAll(configQuotes));

        configManager.deleteFavoriteQuotes(quotes);
        configQuotes = configManager.readFavoriteQuotes();
        for (Instrument q : quotes)
            assertFalse(configQuotes.contains(q));
    }
}
