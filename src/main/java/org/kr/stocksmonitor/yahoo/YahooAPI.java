package org.kr.stocksmonitor.yahoo;

import org.apache.commons.collections4.map.LRUMap;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kr.stocksmonitor.utils.RestUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public class YahooAPI {

    private static final Logger log = LogManager.getLogger(YahooAPI.class);
    private static final YahooAPI instance = new YahooAPI();
    private static final String SEARCH_QUOTES_BASE_URL = "https://query1.finance.yahoo.com/v1/finance/search";
    private static final String CHART_BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final NameValuePair[] SEARCH_QUOTES_CONFIG_PARAMS = {
            new BasicNameValuePair("lang", "de-de"),
            new BasicNameValuePair("region", "DE"),
            new BasicNameValuePair("quotesCount", "10"),
            new BasicNameValuePair("newsCount", "0"),
            new BasicNameValuePair("listsCount", "0"),
            new BasicNameValuePair("enableFuzzyQuery", "false"),
            new BasicNameValuePair("quotesQueryId", "tss_match_phrase_query"),
            new BasicNameValuePair("multiQuoteQueryId", "multi_quote_single_token_query"),
            new BasicNameValuePair("newsQueryId", "news_cie_vespa"),
            new BasicNameValuePair("enableCb", "false"),
            new BasicNameValuePair("enableNavLinks", "false"),
            new BasicNameValuePair("enableEnhancedTrivialQuery", "true"),
            new BasicNameValuePair("enableResearchReports", "false"),
            new BasicNameValuePair("enableCulturalAssets", "false"),
            new BasicNameValuePair("enableLogoUrl", "false"),
            new BasicNameValuePair("", "")
    };
    private static final int NEWS_COUNT = 20;

    // Yahoo reports some listings in fractional sub-units of the main currency.
    // Normalize them to the main unit at parse time so FX conversion is correct
    // and "Native" mode shows e.g. £53.21 instead of "5321 GBp".
    private static final Map<String, SubUnit> SUB_UNIT_CURRENCIES = Map.of(
            "GBp", new SubUnit("GBP", 0.01),  // British pence
            "GBX", new SubUnit("GBP", 0.01),  // alt code for pence
            "ZAc", new SubUnit("ZAR", 0.01),  // South African cents
            "ILA", new SubUnit("ILS", 0.01),  // Israeli agorot
            "ILa", new SubUnit("ILS", 0.01)
    );

    private record SubUnit(String main, double scale) {}

    /**
     * Returns the main currency + value scale to apply, or {@code null}/identity if not a sub-unit.
     */
    private static SubUnit subUnitOf(String currency) {
        if (currency == null || currency.isEmpty()) return null;
        return SUB_UNIT_CURRENCIES.get(currency);
    }

    private static double scale(double value, SubUnit sub) {
        if (sub == null || Double.isNaN(value)) return value;
        return value * sub.scale();
    }

    private static String mainCurrency(String currency, SubUnit sub) {
        return sub == null ? (currency == null ? "" : currency) : sub.main();
    }

    // --- TTL helpers for live-edge caches (history/dividends ending today, snapshots) -----------
    private static final Duration HISTORY_TODAY_TTL = Duration.ofMinutes(5);
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);

    private record Timed<T>(Instant fetched, T value) {
        boolean isFresh(Duration ttl) {
            return Duration.between(fetched, Instant.now()).compareTo(ttl) < 0;
        }
    }

    private final Map<String, List<QuoteItem>> quotesCache = Collections.synchronizedMap(new LRUMap<>(10000));
    private final Map<String, List<NewsItem>> newsCache = Collections.synchronizedMap(new LRUMap<>(2000));
    private final Map<String, Timed<List<HistoricalBar>>> historyCache = Collections.synchronizedMap(new LRUMap<>(2000));
    private final Map<String, Timed<List<Dividend>>> dividendCache = Collections.synchronizedMap(new LRUMap<>(2000));
    private final Map<String, NavigableMap<LocalDate, Double>> fxCache = Collections.synchronizedMap(new LRUMap<>(1000));
    private final Map<String, Timed<QuoteSnapshot>> snapshotCache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Timed<QuoteSnapshot>> eldest) {
            return size() > 1000;
        }
    });

    public static YahooAPI getInstance() {
        return instance;
    }

    private YahooAPI() {
    }

    public List<QuoteItem> getQuotes(String name) throws IOException {
        List<QuoteItem> entry = quotesCache.get(name);
        if (null != entry)
            return entry;
        final NameValuePair[] parameters = Arrays.copyOf(SEARCH_QUOTES_CONFIG_PARAMS, SEARCH_QUOTES_CONFIG_PARAMS.length);
        parameters[parameters.length - 1] = new BasicNameValuePair("q", name);
        String response = RestUtils.getInstance().runQuery(SEARCH_QUOTES_BASE_URL, parameters);
        final JSONObject obj = new JSONObject(response);
        if (!obj.has("quotes")) {
            log.error("Response does not contain the 'quotes' object: {}", response);
            return Collections.emptyList();
        }
        final JSONArray array = obj.getJSONArray("quotes");
        final List<QuoteItem> quotes = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject result = array.getJSONObject(i);
            QuoteItem item = new QuoteItem(result);
            quotes.add(item);
        }
        quotesCache.putIfAbsent(name, quotes);
        return quotes;
    }

    public List<NewsItem> getNews(String symbol) throws IOException {
        List<NewsItem> entry = newsCache.get(symbol);
        if (null != entry) return entry;
        final NameValuePair[] parameters = {
                new BasicNameValuePair("q", symbol),
                new BasicNameValuePair("lang", "en-US"),
                new BasicNameValuePair("region", "US"),
                new BasicNameValuePair("quotesCount", "0"),
                new BasicNameValuePair("newsCount", Integer.toString(NEWS_COUNT)),
                new BasicNameValuePair("listsCount", "0"),
                new BasicNameValuePair("enableFuzzyQuery", "false")
        };
        String response = RestUtils.getInstance().runQuery(SEARCH_QUOTES_BASE_URL, parameters);
        final JSONObject obj = new JSONObject(response);
        if (!obj.has("news")) {
            log.error("Response does not contain the 'news' array: {}", response);
            return Collections.emptyList();
        }
        final JSONArray array = obj.getJSONArray("news");
        final List<NewsItem> news = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            news.add(NewsItem.from(array.getJSONObject(i)));
        }
        newsCache.putIfAbsent(symbol, news);
        return news;
    }

    public QuoteSnapshot getSnapshot(String symbol) throws IOException {
        // Snapshots are live data, but a 30s TTL absorbs rapid currency/tab toggles
        // without making prices visibly stale.
        Timed<QuoteSnapshot> cached = snapshotCache.get(symbol);
        if (cached != null && cached.isFresh(SNAPSHOT_TTL)) return cached.value();

        final String url = CHART_BASE_URL + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        final NameValuePair[] parameters = {
                new BasicNameValuePair("interval", "1d"),
                new BasicNameValuePair("range", "1d")
        };
        String response = RestUtils.getInstance().runQuery(url, false, parameters);
        JSONObject result = firstChartResult(response, symbol);
        if (result == null) return null;
        JSONObject meta = result.optJSONObject("meta");
        if (meta == null) return null;

        QuoteSnapshot snap = QuoteSnapshot.from(meta);
        SubUnit sub = subUnitOf(snap.currency());
        if (sub != null) {
            snap = new QuoteSnapshot(
                    snap.symbol(),
                    snap.longName(),
                    snap.exchangeName(),
                    sub.main(),
                    scale(snap.regularMarketPrice(), sub),
                    scale(snap.previousClose(), sub),
                    scale(snap.regularMarketDayHigh(), sub),
                    scale(snap.regularMarketDayLow(), sub),
                    snap.regularMarketVolume(),  // volume is a count, not a price — don't scale
                    scale(snap.fiftyTwoWeekHigh(), sub),
                    scale(snap.fiftyTwoWeekLow(), sub),
                    snap.regularMarketTime()
            );
        }
        snapshotCache.put(symbol, new Timed<>(Instant.now(), snap));
        return snap;
    }

    public List<HistoricalBar> getHistory(String symbol, LocalDate from, LocalDate to) throws IOException {
        if (from == null || to == null || from.isAfter(to)) return Collections.emptyList();
        final String key = symbol + "|" + from + "|" + to;
        Timed<List<HistoricalBar>> cached = historyCache.get(key);
        if (cached != null && isFreshForRange(cached, to)) return cached.value();

        final ZoneId zone = ZoneId.systemDefault();
        long period1 = from.atStartOfDay(zone).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(zone).toEpochSecond();
        final String url = CHART_BASE_URL + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        final NameValuePair[] parameters = {
                new BasicNameValuePair("period1", Long.toString(period1)),
                new BasicNameValuePair("period2", Long.toString(period2)),
                new BasicNameValuePair("interval", "1d"),
                new BasicNameValuePair("events", "history")
        };
        String response = RestUtils.getInstance().runQuery(url, parameters);
        JSONObject result = firstChartResult(response, symbol);
        if (result == null || !result.has("timestamp")) return Collections.emptyList();

        String rawCurrency = currencyOf(result);
        SubUnit sub = subUnitOf(rawCurrency);
        String currency = mainCurrency(rawCurrency, sub);
        JSONArray timestamps = result.getJSONArray("timestamp");
        JSONObject indicators = result.getJSONObject("indicators");
        JSONObject quote = indicators.getJSONArray("quote").getJSONObject(0);
        JSONArray opens = quote.optJSONArray("open");
        JSONArray highs = quote.optJSONArray("high");
        JSONArray lows = quote.optJSONArray("low");
        JSONArray closes = quote.optJSONArray("close");
        JSONArray volumes = quote.optJSONArray("volume");
        JSONArray adjCloses = null;
        if (indicators.has("adjclose")) {
            adjCloses = indicators.getJSONArray("adjclose").getJSONObject(0).optJSONArray("adjclose");
        }

        List<HistoricalBar> bars = new ArrayList<>(timestamps.length());
        for (int i = 0; i < timestamps.length(); i++) {
            long ts = timestamps.getLong(i);
            LocalDate date = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate();
            bars.add(new HistoricalBar(
                    symbol,
                    currency,
                    date,
                    scale(doubleAt(opens, i), sub),
                    scale(doubleAt(highs, i), sub),
                    scale(doubleAt(lows, i), sub),
                    scale(doubleAt(closes, i), sub),
                    scale(doubleAt(adjCloses, i), sub),
                    longAt(volumes, i)
            ));
        }
        historyCache.put(key, new Timed<>(Instant.now(), bars));
        return bars;
    }

    public List<Dividend> getDividends(String symbol, LocalDate from, LocalDate to) throws IOException {
        if (from == null || to == null || from.isAfter(to)) return Collections.emptyList();
        final String key = symbol + "|" + from + "|" + to;
        Timed<List<Dividend>> cached = dividendCache.get(key);
        if (cached != null && isFreshForRange(cached, to)) return cached.value();

        final ZoneId zone = ZoneId.systemDefault();
        long period1 = from.atStartOfDay(zone).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(zone).toEpochSecond();
        final String url = CHART_BASE_URL + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        final NameValuePair[] parameters = {
                new BasicNameValuePair("period1", Long.toString(period1)),
                new BasicNameValuePair("period2", Long.toString(period2)),
                new BasicNameValuePair("interval", "1d"),
                new BasicNameValuePair("events", "div")
        };
        String response = RestUtils.getInstance().runQuery(url, parameters);
        JSONObject result = firstChartResult(response, symbol);
        if (result == null) return Collections.emptyList();

        String rawCurrency = currencyOf(result);
        SubUnit sub = subUnitOf(rawCurrency);
        String currency = mainCurrency(rawCurrency, sub);
        // Yahoo omits 'events' / 'events.dividends' when no payouts fall in the range — that's a real "no data" answer, cache it.
        JSONObject events = result.optJSONObject("events");
        JSONObject dividends = events == null ? null : events.optJSONObject("dividends");
        if (dividends == null) {
            dividendCache.put(key, new Timed<>(Instant.now(), Collections.emptyList()));
            return Collections.emptyList();
        }

        List<Dividend> bars = new ArrayList<>();
        for (String tsKey : dividends.keySet()) {
            JSONObject d = dividends.optJSONObject(tsKey);
            if (d == null) continue;
            long ts = d.optLong("date", 0);
            if (ts == 0) {
                try { ts = Long.parseLong(tsKey); } catch (NumberFormatException ignore) { continue; }
            }
            double amount = d.optDouble("amount", Double.NaN);
            if (Double.isNaN(amount)) continue;
            LocalDate date = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate();
            bars.add(new Dividend(symbol, currency, date, scale(amount, sub)));
        }
        bars.sort(Comparator.comparing(Dividend::date));
        dividendCache.put(key, new Timed<>(Instant.now(), bars));
        return bars;
    }

    public NavigableMap<LocalDate, Double> getFxRates(String fromCcy, String toCcy, LocalDate from, LocalDate to) throws IOException {
        if (fromCcy == null || fromCcy.isEmpty() || toCcy == null || toCcy.isEmpty()) {
            return Collections.emptyNavigableMap();
        }
        if (fromCcy.equalsIgnoreCase(toCcy)) {
            return Collections.emptyNavigableMap(); // caller treats empty/missing as 1.0
        }
        if (from == null || to == null || from.isAfter(to)) {
            return Collections.emptyNavigableMap();
        }
        final String pair = (fromCcy + toCcy).toUpperCase(Locale.ROOT) + "=X";
        final String key = pair + "|" + from + "|" + to;
        NavigableMap<LocalDate, Double> cached = fxCache.get(key);
        if (cached != null) return cached;

        final ZoneId zone = ZoneId.systemDefault();
        long period1 = from.atStartOfDay(zone).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(zone).toEpochSecond();
        final String url = CHART_BASE_URL + URLEncoder.encode(pair, StandardCharsets.UTF_8);
        final NameValuePair[] parameters = {
                new BasicNameValuePair("period1", Long.toString(period1)),
                new BasicNameValuePair("period2", Long.toString(period2)),
                new BasicNameValuePair("interval", "1d")
        };
        String response = RestUtils.getInstance().runQuery(url, parameters);
        JSONObject result = firstChartResult(response, pair);
        if (result == null || !result.has("timestamp")) {
            // 404 / error / no data — cache an empty map so we don't retry on every render.
            fxCache.putIfAbsent(key, Collections.emptyNavigableMap());
            return Collections.emptyNavigableMap();
        }

        JSONArray timestamps = result.getJSONArray("timestamp");
        JSONObject indicators = result.getJSONObject("indicators");
        JSONObject quote = indicators.getJSONArray("quote").getJSONObject(0);
        JSONArray closes = quote.optJSONArray("close");

        NavigableMap<LocalDate, Double> rates = new TreeMap<>();
        for (int i = 0; i < timestamps.length(); i++) {
            double rate = doubleAt(closes, i);
            if (Double.isNaN(rate) || rate <= 0.0) continue;
            LocalDate date = Instant.ofEpochSecond(timestamps.getLong(i)).atZone(zone).toLocalDate();
            rates.put(date, rate);
        }
        fxCache.putIfAbsent(key, rates);
        return rates;
    }

    /** Force-evicts cached entries for one symbol so the next fetch hits the network. */
    public void invalidate(String symbol) {
        if (symbol == null) return;
        snapshotCache.remove(symbol);
        // history / dividend keys are prefixed with symbol|
        String prefix = symbol + "|";
        historyCache.keySet().removeIf(k -> k.startsWith(prefix));
        dividendCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Past ranges are immutable — cache forever. Ranges ending today/future are short-TTL. */
    private static boolean isFreshForRange(Timed<?> entry, LocalDate to) {
        if (to.isBefore(LocalDate.now())) return true;
        return entry.isFresh(HISTORY_TODAY_TTL);
    }

    private static String currencyOf(JSONObject chartResult) {
        JSONObject meta = chartResult.optJSONObject("meta");
        return meta == null ? "" : meta.optString("currency", "");
    }

    private static JSONObject firstChartResult(String response, String symbol) {
        JSONObject obj = new JSONObject(response);
        JSONObject chart = obj.optJSONObject("chart");
        if (chart == null) {
            log.error("Chart response missing 'chart' for {}: {}", symbol, response);
            return null;
        }
        if (!chart.isNull("error")) {
            log.error("Chart returned error for {}: {}", symbol, chart.get("error"));
            return null;
        }
        JSONArray results = chart.optJSONArray("result");
        if (results == null || results.isEmpty()) return null;
        return results.getJSONObject(0);
    }

    private static double doubleAt(JSONArray arr, int i) {
        if (arr == null || i >= arr.length() || arr.isNull(i)) return Double.NaN;
        return arr.getDouble(i);
    }

    private static long longAt(JSONArray arr, int i) {
        if (arr == null || i >= arr.length() || arr.isNull(i)) return 0L;
        return arr.getLong(i);
    }
}
