package org.kr.stocksmonitor.polygon;

import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kr.stocksmonitor.config.ConfigManager;
import org.kr.stocksmonitor.config.Tuple2;
import org.kr.stocksmonitor.exceptions.RestCallException;
import org.kr.stocksmonitor.utils.RestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class PolygonAPI {
    private static final Logger logger = LogManager.getLogger(PolygonAPI.class);

    private static final String baseURL = "https://api.polygon.io";
    private static final String tickerTypeGET = "/v3/reference/tickers/types";
    private static final String tickersGET = "/v3/reference/tickers";
    private static final String tickerNewsGET = "/v2/reference/news";
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private String apiKey;

    private final RestUtils restUtils = RestUtils.getInstance();

    public PolygonAPI() {
        this.apiKey = ConfigManager.getInstance().readPolygonApiKey();
    }

    public List<TickerType> loadTickerTypes(String assetClass) throws RestCallException {
        final List<NameValuePair> parameters = List.of(new BasicNameValuePair("asset_class", assetClass));
        final String response = restUtils.callPolygonEndpoint(baseURL, tickerTypeGET, apiKey, parameters);
        return parseJsonResponseTickerTypes(response);
    }

    public Set<Ticker> loadAllTickers(ProgressCallback callback) throws RestCallException {
        final List<NameValuePair> parameters = Arrays.asList(
                new BasicNameValuePair("limit", "1000"),
                new BasicNameValuePair("sort", "ticker")
        );

        final Set<Ticker> tickers = new HashSet<>();

        String nextUrl = baseURL;
        while (null != nextUrl && !nextUrl.isEmpty()) {
            String response;
            if (tickers.isEmpty()) //first call
                response = restUtils.callPolygonEndpoint(baseURL, tickersGET, apiKey, parameters);
            else
                response = restUtils.callPolygonEndpoint(nextUrl, "", apiKey, Collections.emptyList());
            var res = parseResponseHeader(response);
            nextUrl = res.getFirst();
            var results = res.getSecond();

            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                Ticker ticker = new Ticker(result);
                tickers.add(ticker);
            }

            callback.onProgressUpdate(String.format("Items collected: %d", tickers.size()));
        }

        return tickers;
    }

    public List<NewsArticle> getTickerNews(Ticker ticker, LocalDate start, LocalDate end) throws RestCallException {
        final List<NameValuePair> parameters = Arrays.asList(
                new BasicNameValuePair("published_utc.gte", start.format(dateFormatter)),
                new BasicNameValuePair("published_utc.lte", end.format(dateFormatter)),
                new BasicNameValuePair("ticker", ticker.getTicker()),
                new BasicNameValuePair("limit", "1000"),
                new BasicNameValuePair("sort", "published_utc")
        );

        return getTickerNews(parameters);
    }

    List<NewsArticle> getTickerNews(List<NameValuePair> parameters) throws RestCallException {
        String nextUrl = baseURL;
        List<NewsArticle> news = new ArrayList<>();
        while (null != nextUrl && !nextUrl.isEmpty()) {
            String response;
            if (news.isEmpty())
                response = restUtils.callPolygonEndpoint(baseURL, tickerNewsGET, apiKey, parameters);
            else
                response = restUtils.callPolygonEndpoint(nextUrl, "", apiKey, Collections.emptyList());
            var res = parseResponseHeader(response);
            nextUrl = res.getFirst();
            var results = res.getSecond();

            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                news.add(new NewsArticle(result));
            }
        }
        return news;
    }

    private Tuple2<String, JSONArray> parseResponseHeader(String response) throws RestCallException {
        logger.debug("parsing tickers response...");
        JSONObject jsonResponse = new JSONObject(response);
        String status = jsonResponse.getString("status");
        if (!status.equals("OK")) {
            String error = jsonResponse.getString("error");
            throw new RestCallException("Status is not OK: " + error);
        }
        JSONArray results = jsonResponse.getJSONArray("results");
        int count = jsonResponse.optInt("count", results.length());
        if (count != results.length()) throw new RestCallException("Wrong results count");
        String nextUrl = jsonResponse.optString("next_url");
        if (!nextUrl.isEmpty())
            logger.debug("next url present, will be calling next url...");
        return new Tuple2<>(nextUrl, results);
    }

    private List<TickerType> parseJsonResponseTickerTypes(String response) {
        JSONObject json = new JSONObject(response);
        String status = json.getString("status");
        if (!"OK".equals(status))
            return new ArrayList<>();

        if (json.isNull("results")) return new ArrayList<>();
        JSONArray results = json.getJSONArray("results");
        List<TickerType> tickerTypes = new ArrayList<>(results.length());
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.getJSONObject(i);
            String code = result.getString("code");
            String description = result.getString("description");
            String assetClass = result.getString("asset_class");
            String locale = result.getString("locale");

            tickerTypes.add(new TickerType(code, description, assetClass, locale));
        }

        return tickerTypes;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void updateApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public interface ProgressCallback {
        void onProgressUpdate(String progress);
    }
}
