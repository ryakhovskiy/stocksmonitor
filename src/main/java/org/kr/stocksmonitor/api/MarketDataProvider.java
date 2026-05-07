package org.kr.stocksmonitor.api;

import org.kr.stocksmonitor.model.Dividend;
import org.kr.stocksmonitor.model.HistoricalBar;
import org.kr.stocksmonitor.model.Instrument;
import org.kr.stocksmonitor.model.NewsItem;
import org.kr.stocksmonitor.model.QuoteSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Strategy interface for a market-data backend. Concrete implementations adapt an upstream
 * service (Yahoo Finance, Alpha Vantage, Finnhub, an internal feed, …) to the provider-neutral
 * domain models in {@code org.kr.stocksmonitor.model}.
 *
 * <p>Discovered at runtime via {@link java.util.ServiceLoader}; see
 * {@code META-INF/services/org.kr.stocksmonitor.api.MarketDataProvider} and the JPMS
 * {@code provides} directive in {@code module-info.java}.</p>
 *
 * <p>Implementations should be stateless w.r.t. UI concerns and return empty collections (not null)
 * for unsupported operations. Capability gating is advertised via {@link #capabilities()}.</p>
 */
public interface MarketDataProvider {

    /** Stable identifier persisted alongside each {@link Instrument} to route data calls. */
    String id();

    /** Human-readable name shown in UI affordances (provider picker, columns). */
    String displayName();

    /** Set of supported operations; the controller will call only these and skip others gracefully. */
    Set<Capability> capabilities();

    List<Instrument> searchInstruments(String query) throws DataAccessException;

    List<NewsItem> getNews(String symbol) throws DataAccessException;

    QuoteSnapshot getSnapshot(String symbol) throws DataAccessException;

    List<HistoricalBar> getHistory(String symbol, LocalDate from, LocalDate to) throws DataAccessException;

    List<Dividend> getDividends(String symbol, LocalDate from, LocalDate to) throws DataAccessException;

    /** Force-evict cached entries for one symbol so the next fetch hits the upstream. */
    void invalidate(String symbol);
}
