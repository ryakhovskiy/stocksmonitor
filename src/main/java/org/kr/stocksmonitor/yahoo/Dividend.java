package org.kr.stocksmonitor.yahoo;

import java.time.LocalDate;

public record Dividend(String symbol, LocalDate date, double amount) {
}
