package org.kr.stocksmonitor.yahoo;

import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kr.stocksmonitor.api.Capability;
import org.kr.stocksmonitor.api.DataAccessException;
import org.kr.stocksmonitor.api.FxRateProvider;
import org.kr.stocksmonitor.api.MarketDataProvider;
import org.kr.stocksmonitor.model.Dividend;
import org.kr.stocksmonitor.model.HistoricalBar;
import org.kr.stocksmonitor.model.Instrument;
import org.kr.stocksmonitor.model.NewsItem;
import org.kr.stocksmonitor.model.QuoteSnapshot;
import org.kr.stocksmonitor.utils.RestUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Yahoo Finance implementation of {@link MarketDataProvider} + {@link FxRateProvider}.
 * Pure HTTP + parse — caching lives in {@link org.kr.stocksmonitor.api.CachingMarketDataProvider}.
 *
 * <p>Discovered at runtime via {@link java.util.ServiceLoader}; see
 * {@code module-info.java} ({@code provides ... with}) and the
 * {@code META-INF/services/org.kr.stocksmonitor.api.MarketDataProvider} file. The public no-arg
 * constructor is required by {@code ServiceLoader}.</p>
 */
public final class YahooMarketDataProvider implements MarketDataProvider, FxRateProvider {

    public static final String ID = "yahoo";

    private static final Logger log = LogManager.getLogger(YahooMarketDataProvider.class);
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
            "GBp", new SubUnit("GBP", 0.01),
            "GBX", new SubUnit("GBP", 0.01),
            "ZAc", new SubUnit("ZAR", 0.01),
            "ILA", new SubUnit("ILS", 0.01),
            "ILa", new SubUnit("ILS", 0.01)
    );

    private record SubUnit(String main, double scale) {}

    private static final Set<Capability> CAPS = Collections.unmodifiableSet(EnumSet.of(
            Capability.SEARCH, Capability.SNAPSHOT, Capability.HISTORY, Capability.DIVIDENDS, Capability.NEWS));

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public YahooMarketDataProvider() {}

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Yahoo Finance"; }
    @Override public Set<Capability> capabilities() { return CAPS; }

    @Override
    public List<Instrument> searchInstruments(String query) throws DataAccessException {
        try {
            final NameValuePair[] parameters = Arrays.copyOf(SEARCH_QUOTES_CONFIG_PARAMS, SEARCH_QUOTES_CONFIG_PARAMS.length);
            parameters[parameters.length - 1] = new BasicNameValuePair("q", query);
            String response = RestUtils.getInstance().runQuery(SEARCH_QUOTES_BASE_URL, parameters);
            final JSONObject obj = new JSONObject(response);
            if (!obj.has("quotes")) {
                log.error("Response does not contain the 'quotes' object: {}", response);
                return Collections.emptyList();
            }
            final JSONArray array = obj.getJSONArray("quotes");
            final List<Instrument> quotes = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject result = array.getJSONObject(i);
                // Inject providerId into the JSON before constructing — keeps Instrument's JSON ctor uniform.
                result.put("providerId", ID);
                quotes.add(new Instrument(result));
            }
            return quotes;
        } catch (IOException e) {
            throw new DataAccessException("Yahoo search failed for: " + query, e);
        }
    }

    @Override
    public List<NewsItem> getNews(String symbol) throws DataAccessException {
        try {
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
            return news;
        } catch (IOException e) {
            throw new DataAccessException("Yahoo news failed for: " + symbol, e);
        }
    }

    @Override
    public QuoteSnapshot getSnapshot(String symbol) throws DataAccessException {
        try {
            final String url = CHART_BASE_URL + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
            final NameValuePair[] parameters = {
                    new BasicNameValuePair("interval", "1d"),
                    new BasicNameValuePair("range", "1d")
            };
            // Snapshots are live data — bypass the HTTP-level cache; the provider-level decorator owns TTL.
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
            return snap;
        } catch (IOException e) {
            throw new DataAccessException("Yahoo snapshot failed for: " + symbol, e);
        }
    }

    @Override
    public List<HistoricalBar> getHistory(String symbol, LocalDate from, LocalDate to) throws DataAccessException {
        if (from == null || to == null || from.isAfter(to)) return Collections.emptyList();
        try {
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
            return bars;
        } catch (IOException e) {
            throw new DataAccessException("Yahoo history failed for: " + symbol, e);
        }
    }

    @Override
    public List<Dividend> getDividends(String symbol, LocalDate from, LocalDate to) throws DataAccessException {
        if (from == null || to == null || from.isAfter(to)) return Collections.emptyList();
        try {
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
            // Yahoo omits 'events' / 'events.dividends' when no payouts fall in the range — that's a real "no data" answer.
            JSONObject events = result.optJSONObject("events");
            JSONObject dividends = events == null ? null : events.optJSONObject("dividends");
            if (dividends == null) return Collections.emptyList();

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
            return bars;
        } catch (IOException e) {
            throw new DataAccessException("Yahoo dividends failed for: " + symbol, e);
        }
    }

    @Override
    public NavigableMap<LocalDate, Double> getFxRates(String fromCcy, String toCcy, LocalDate from, LocalDate to)
            throws DataAccessException {
        if (fromCcy == null || fromCcy.isEmpty() || toCcy == null || toCcy.isEmpty()) {
            return Collections.emptyNavigableMap();
        }
        if (fromCcy.equalsIgnoreCase(toCcy)) {
            return Collections.emptyNavigableMap(); // caller treats empty/missing as 1.0
        }
        if (from == null || to == null || from.isAfter(to)) {
            return Collections.emptyNavigableMap();
        }
        try {
            final String pair = (fromCcy + toCcy).toUpperCase(Locale.ROOT) + "=X";
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
            return rates;
        } catch (IOException e) {
            throw new DataAccessException("Yahoo FX failed: " + fromCcy + "->" + toCcy, e);
        }
    }

    @Override
    public void invalidate(String symbol) {
        // Pure HTTP+parse — no provider-side state to evict. The decorator handles its own caches.
    }

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
