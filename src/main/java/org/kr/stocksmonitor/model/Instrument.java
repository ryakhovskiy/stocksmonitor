package org.kr.stocksmonitor.model;

import org.json.JSONObject;

import java.util.Objects;

/**
 * Provider-neutral identifier for a tradable instrument.
 * Replaces the prior {@code yahoo.QuoteItem}; carries a {@code providerId} so the controller
 * can route data calls to the right backend.
 */
public final class Instrument {

    /** Default provider id for legacy {@code .favquotes.json} rows that pre-date the multi-provider work. */
    public static final String LEGACY_DEFAULT_PROVIDER_ID = "yahoo";

    private final String providerId;
    private final String exchange;
    private final String shortname;
    private final String quoteType;
    private final String symbol;
    private final String index;
    private final String typeDisp;
    private final String longname;
    private final String exchDisp;
    private final boolean isYahooFinance;

    public Instrument(JSONObject json) {
        this.providerId = json.optString("providerId", LEGACY_DEFAULT_PROVIDER_ID);
        this.exchange = json.optString("exchange", "");
        this.shortname = json.optString("shortname", "");
        this.quoteType = json.optString("quoteType", "");
        this.symbol = json.optString("symbol", "");
        this.index = json.optString("index", "");
        this.typeDisp = json.optString("typeDisp", "");
        this.longname = json.optString("longname", "");
        this.exchDisp = json.optString("exchDisp", "");
        this.isYahooFinance = json.optBoolean("isYahooFinance", false);
    }

    public Instrument(String providerId, String exchange, String shortname, String quoteType, String symbol,
                      String index, String typeDisp, String longname, String exchDisp, boolean isYahooFinance) {
        this.providerId = providerId == null ? LEGACY_DEFAULT_PROVIDER_ID : providerId;
        this.exchange = exchange;
        this.shortname = shortname;
        this.quoteType = quoteType;
        this.symbol = symbol;
        this.index = index;
        this.typeDisp = typeDisp;
        this.longname = longname;
        this.exchDisp = exchDisp;
        this.isYahooFinance = isYahooFinance;
    }

    public String getProviderId()   { return providerId; }
    public String getExchange()     { return exchange; }
    public String getShortname()    { return shortname; }
    public String getQuoteType()    { return quoteType; }
    public String getSymbol()       { return symbol; }
    public String getIndex()        { return index; }
    public String getTypeDisp()     { return typeDisp; }
    public String getLongname()     { return longname; }
    public String getExchDisp()     { return exchDisp; }
    public boolean isYahooFinance() { return isYahooFinance; }

    @Override
    public String toString() {
        return String.format("{ %s/%s | %s | %s | %s | %s }",
                providerId, symbol, quoteType, shortname, exchange, typeDisp);
    }

    public JSONObject toJsonObject() {
        final JSONObject obj = new JSONObject();
        obj.put("providerId", providerId);
        obj.put("exchange", exchange);
        obj.put("shortname", shortname);
        obj.put("quoteType", quoteType);
        obj.put("symbol", symbol);
        obj.put("index", index);
        obj.put("typeDisp", typeDisp);
        obj.put("longname", longname);
        obj.put("exchDisp", exchDisp);
        obj.put("isYahooFinance", isYahooFinance);
        return obj;
    }

    public String toJsonString() {
        return toJsonObject().toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, symbol);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Instrument other)) return false;
        return Objects.equals(this.providerId, other.providerId)
                && Objects.equals(this.symbol, other.symbol);
    }
}
