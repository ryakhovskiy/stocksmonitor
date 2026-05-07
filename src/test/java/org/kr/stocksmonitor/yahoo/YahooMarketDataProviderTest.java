package org.kr.stocksmonitor.yahoo;

import org.junit.jupiter.api.Test;
import org.kr.stocksmonitor.api.DataAccessException;
import org.kr.stocksmonitor.model.Instrument;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooMarketDataProviderTest {

    @Test
    public void testSearchInstruments() throws DataAccessException {
        YahooMarketDataProvider api = new YahooMarketDataProvider();
        List<Instrument> items = api.searchInstruments("SPY");
        assertNotNull(items);
        assertFalse(items.isEmpty());
        // Every result should carry our provider id, not the legacy default.
        assertEquals(YahooMarketDataProvider.ID, items.get(0).getProviderId());
    }
}
