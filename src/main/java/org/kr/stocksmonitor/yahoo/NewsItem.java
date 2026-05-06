package org.kr.stocksmonitor.yahoo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record NewsItem(String uuid,
                       String title,
                       String publisher,
                       String link,
                       Instant publishTime,
                       List<String> relatedTickers) {

    public static NewsItem from(JSONObject json) {
        long ts = json.optLong("providerPublishTime", 0);
        List<String> tickers = List.of();
        if (json.has("relatedTickers")) {
            JSONArray arr = json.getJSONArray("relatedTickers");
            tickers = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) tickers.add(arr.getString(i));
        }
        return new NewsItem(
                json.optString("uuid", ""),
                json.optString("title", ""),
                json.optString("publisher", ""),
                json.optString("link", ""),
                ts > 0 ? Instant.ofEpochSecond(ts) : Instant.EPOCH,
                tickers
        );
    }
}
