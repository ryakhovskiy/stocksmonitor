package org.kr.stocksmonitor.api;

import org.junit.jupiter.api.Test;
import org.kr.stocksmonitor.yahoo.YahooMarketDataProvider;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {

    @Test
    void yahooProviderIsDiscoveredViaSpi() {
        ProviderRegistry registry = ProviderRegistry.getInstance();

        assertFalse(registry.all().isEmpty(), "ServiceLoader found no providers — check META-INF/services and module-info");
        assertTrue(registry.find(YahooMarketDataProvider.ID).isPresent(),
                "Yahoo provider not registered under id '" + YahooMarketDataProvider.ID + "'");

        // Whatever ServiceLoader handed us, the registry must have wrapped it in CachingMarketDataProvider.
        MarketDataProvider yahoo = registry.get(YahooMarketDataProvider.ID);
        assertInstanceOf(CachingMarketDataProvider.class, yahoo);
        assertEquals(YahooMarketDataProvider.ID, yahoo.id());

        // FX-capable providers should be exposed via the FX registry too — Yahoo implements both.
        assertTrue(registry.findFx(YahooMarketDataProvider.ID).isPresent());
        assertTrue(registry.getDefaultFx().isPresent());
    }
}
