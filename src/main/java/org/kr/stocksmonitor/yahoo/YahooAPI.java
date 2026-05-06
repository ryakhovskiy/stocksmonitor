package org.kr.stocksmonitor.yahoo;

import org.apache.commons.collections4.map.LRUMap;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kr.stocksmonitor.utils.RestUtils2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class YahooAPI {

    private static final Logger log = LogManager.getLogger(YahooAPI.class);
    private static final YahooAPI instance = new YahooAPI();
    private static final String SEARCH_QUOTES_BASE_URL = "https://query1.finance.yahoo.com/v1/finance/search";
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
        String response = RestUtils2.getInstance().runQuery(SEARCH_QUOTES_BASE_URL, parameters);
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
        String response = RestUtils2.getInstance().runQuery(SEARCH_QUOTES_BASE_URL, parameters);
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
}
