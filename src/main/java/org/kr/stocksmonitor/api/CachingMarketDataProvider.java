package org.kr.stocksmonitor.api;

import org.apache.commons.collections4.map.LRUMap;
import org.kr.stocksmonitor.model.Dividend;
import org.kr.stocksmonitor.model.HistoricalBar;
import org.kr.stocksmonitor.model.Instrument;
import org.kr.stocksmonitor.model.NewsItem;
import org.kr.stocksmonitor.model.QuoteSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic LRU + TTL caching {@link MarketDataProvider} decorator. Keeps the same caching semantics
 * the Yahoo provider previously had, but written once and reusable for any backend.
 *
 * <ul>
 *   <li>Snapshots: 30 s TTL — absorbs rapid currency/tab toggles without staling the price.</li>
 *   <li>History / dividends: immutable for ranges ending in the past; 5 min TTL when {@code to >= today}.</li>
 *   <li>Quotes / news: cached forever (LRU only).</li>
 *   <li>Empty / "no data" results are cached too, to avoid retry storms on every render.</li>
 * </ul>
 */
public final class CachingMarketDataProvider implements MarketDataProvider {

    private static final Duration HISTORY_TODAY_TTL = Duration.ofMinutes(5);
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);

    private record Timed<T>(Instant fetched, T value) {
        boolean isFresh(Duration ttl) {
            return Duration.between(fetched, Instant.now()).compareTo(ttl) < 0;
        }
    }

    private final MarketDataProvider delegate;

    private final Map<String, List<Instrument>> quotesCache = Collections.synchronizedMap(new LRUMap<>(10000));
    private final Map<String, List<NewsItem>> newsCache = Collections.synchronizedMap(new LRUMap<>(2000));
    private final Map<String, Timed<List<HistoricalBar>>> historyCache = Collections.synchronizedMap(new LRUMap<>(2000));
    private final Map<String, Timed<List<Dividend>>> dividendCache = Collections.synchronizedMap(new LRUMap<>(2000));
    private final Map<String, Timed<QuoteSnapshot>> snapshotCache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Timed<QuoteSnapshot>> eldest) {
            return size() > 1000;
        }
    });

    public CachingMarketDataProvider(MarketDataProvider delegate) {
        this.delegate = delegate;
    }

    public MarketDataProvider delegate() { return delegate; }

    @Override public String id() { return delegate.id(); }
    @Override public String displayName() { return delegate.displayName(); }
    @Override public Set<Capability> capabilities() { return delegate.capabilities(); }

    @Override
    public List<Instrument> searchInstruments(String query) throws DataAccessException {
        List<Instrument> hit = quotesCache.get(query);
        if (hit != null) return hit;
        List<Instrument> fresh = delegate.searchInstruments(query);
        quotesCache.putIfAbsent(query, fresh);
        return fresh;
    }

    @Override
    public List<NewsItem> getNews(String symbol) throws DataAccessException {
        List<NewsItem> hit = newsCache.get(symbol);
        if (hit != null) return hit;
        List<NewsItem> fresh = delegate.getNews(symbol);
        newsCache.putIfAbsent(symbol, fresh);
        return fresh;
    }

    @Override
    public QuoteSnapshot getSnapshot(String symbol) throws DataAccessException {
        Timed<QuoteSnapshot> hit = snapshotCache.get(symbol);
        if (hit != null && hit.isFresh(SNAPSHOT_TTL)) return hit.value();
        QuoteSnapshot fresh = delegate.getSnapshot(symbol);
        if (fresh != null) snapshotCache.put(symbol, new Timed<>(Instant.now(), fresh));
        return fresh;
    }

    @Override
    public List<HistoricalBar> getHistory(String symbol, LocalDate from, LocalDate to) throws DataAccessException {
        if (from == null || to == null || from.isAfter(to)) return Collections.emptyList();
        String key = symbol + "|" + from + "|" + to;
        Timed<List<HistoricalBar>> hit = historyCache.get(key);
        if (hit != null && isFreshForRange(hit, to)) return hit.value();
        List<HistoricalBar> fresh = delegate.getHistory(symbol, from, to);
        historyCache.put(key, new Timed<>(Instant.now(), fresh));
        return fresh;
    }

    @Override
    public List<Dividend> getDividends(String symbol, LocalDate from, LocalDate to) throws DataAccessException {
        if (from == null || to == null || from.isAfter(to)) return Collections.emptyList();
        String key = symbol + "|" + from + "|" + to;
        Timed<List<Dividend>> hit = dividendCache.get(key);
        if (hit != null && isFreshForRange(hit, to)) return hit.value();
        List<Dividend> fresh = delegate.getDividends(symbol, from, to);
        dividendCache.put(key, new Timed<>(Instant.now(), fresh));
        return fresh;
    }

    @Override
    public void invalidate(String symbol) {
        if (symbol == null) return;
        snapshotCache.remove(symbol);
        String prefix = symbol + "|";
        historyCache.keySet().removeIf(k -> k.startsWith(prefix));
        dividendCache.keySet().removeIf(k -> k.startsWith(prefix));
        delegate.invalidate(symbol);
    }

    /** Past ranges are immutable — cache forever. Ranges ending today/future are short-TTL. */
    private static boolean isFreshForRange(Timed<?> entry, LocalDate to) {
        if (to.isBefore(LocalDate.now())) return true;
        return entry.isFresh(HISTORY_TODAY_TTL);
    }
}
