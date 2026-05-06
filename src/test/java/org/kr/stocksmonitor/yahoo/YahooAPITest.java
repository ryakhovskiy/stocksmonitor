package org.kr.stocksmonitor.yahoo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooAPITest {

    @Test
    public void testGetQuotes() throws IOException {
        YahooAPI api = YahooAPI.getInstance();
        List<QuoteItem> items = api.getQuotes("SPY");
        assertNotNull(items);
        assertFalse(items.isEmpty());
    }
}
