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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    private final Map<String, List<QuoteItem>> quotesCache = Collections.synchronizedMap(new LRUMap<>(10000));
    private final Map<String, List<NewsItem>> newsCache = Collections.synchronizedMap(new LRUMap<>(2000));
    private final Map<String, List<HistoricalBar>> historyCache = Collections.synchronizedMap(new LRUMap<>(2000));

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
        // Snapshots represent live data — never cached at the HTTP layer.
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
        return QuoteSnapshot.from(meta);
    }

    public List<HistoricalBar> getHistory(String symbol, LocalDate from, LocalDate to) throws IOException {
        if (from == null || to == null || from.isAfter(to)) return Collections.emptyList();
        final String key = symbol + "|" + from + "|" + to;
        List<HistoricalBar> cached = historyCache.get(key);
        if (cached != null) return cached;

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
                    date,
                    doubleAt(opens, i),
                    doubleAt(highs, i),
                    doubleAt(lows, i),
                    doubleAt(closes, i),
                    doubleAt(adjCloses, i),
                    longAt(volumes, i)
            ));
        }
        historyCache.putIfAbsent(key, bars);
        return bars;
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
