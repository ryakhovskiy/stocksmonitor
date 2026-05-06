package org.kr.stocksmonitor.yahoo;

import org.json.JSONObject;

import java.time.Instant;

public record QuoteSnapshot(String symbol,
                            String longName,
                            String exchangeName,
                            String currency,
                            double regularMarketPrice,
                            double previousClose,
                            double regularMarketDayHigh,
                            double regularMarketDayLow,
                            long regularMarketVolume,
                            double fiftyTwoWeekHigh,
                            double fiftyTwoWeekLow,
                            Instant regularMarketTime) {

    public static QuoteSnapshot from(JSONObject meta) {
        long ts = meta.optLong("regularMarketTime", 0);
        return new QuoteSnapshot(
                meta.optString("symbol", ""),
                meta.optString("longName", meta.optString("shortName", "")),
                meta.optString("exchangeName", ""),
                meta.optString("currency", ""),
                meta.optDouble("regularMarketPrice", Double.NaN),
                meta.optDouble("previousClose", meta.optDouble("chartPreviousClose", Double.NaN)),
                meta.optDouble("regularMarketDayHigh", Double.NaN),
                meta.optDouble("regularMarketDayLow", Double.NaN),
                meta.optLong("regularMarketVolume", 0L),
                meta.optDouble("fiftyTwoWeekHigh", Double.NaN),
                meta.optDouble("fiftyTwoWeekLow", Double.NaN),
                ts > 0 ? Instant.ofEpochSecond(ts) : Instant.EPOCH
        );
    }

    public double change() {
        if (Double.isNaN(regularMarketPrice) || Double.isNaN(previousClose)) return Double.NaN;
        return regularMarketPrice - previousClose;
    }

    public double changePercent() {
        double c = change();
        if (Double.isNaN(c) || previousClose == 0.0) return Double.NaN;
        return c / previousClose * 100.0;
    }
}
