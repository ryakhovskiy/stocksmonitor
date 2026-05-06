package org.kr.stocksmonitor.yahoo;

import java.time.LocalDate;

public record Dividend(String symbol, String currency, LocalDate date, double amount) {
}
