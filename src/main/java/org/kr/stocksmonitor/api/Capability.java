package org.kr.stocksmonitor.api;

/**
 * Optional features a {@link MarketDataProvider} may support.
 * The controller uses these to gate UI affordances (e.g. show "no dividends" instead of erroring).
 */
public enum Capability {
    SEARCH,
    SNAPSHOT,
    HISTORY,
    DIVIDENDS,
    NEWS
}
