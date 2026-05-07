module org.kr.finmonitor {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.json;
    requires org.apache.logging.log4j;
    requires org.apache.commons.collections4;
    requires org.apache.httpcomponents.core5.httpcore5;
    requires org.apache.httpcomponents.client5.httpclient5;

    exports org.kr.stocksmonitor;
    exports org.kr.stocksmonitor.api;
    exports org.kr.stocksmonitor.config;
    exports org.kr.stocksmonitor.model;

    // FXML reflectively constructs/instantiates the controller and reads @FXML fields.
    opens org.kr.stocksmonitor to javafx.fxml;

    // Pluggable market-data provider discovery (Strategy + ServiceLoader).
    uses org.kr.stocksmonitor.api.MarketDataProvider;
    provides org.kr.stocksmonitor.api.MarketDataProvider
            with org.kr.stocksmonitor.yahoo.YahooMarketDataProvider;
}
