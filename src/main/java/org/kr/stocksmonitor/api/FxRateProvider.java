package org.kr.stocksmonitor.api;

import java.time.LocalDate;
import java.util.NavigableMap;

/**
 * FX-rate look-up split out from {@link MarketDataProvider} so a backend can offer instruments
 * without offering FX (and vice-versa). One global FX provider is used regardless of which
 * provider owns a given instrument — see plan rationale.
 */
public interface FxRateProvider {

    /**
     * @return a {@link NavigableMap} of trading-date → {@code fromCcy/toCcy} close rate.
     *         Empty/missing means "no conversion" — callers should treat as identity.
     */
    NavigableMap<LocalDate, Double> getFxRates(String fromCcy, String toCcy, LocalDate from, LocalDate to)
            throws DataAccessException;
}
