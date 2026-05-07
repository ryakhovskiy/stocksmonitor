package org.kr.stocksmonitor.model;

import java.time.LocalDate;

public record HistoricalBar(String symbol,
                            String currency,
                            LocalDate date,
                            double open,
                            double high,
                            double low,
                            double close,
                            double adjClose,
                            long volume) {
}
