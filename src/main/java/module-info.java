module org.kr.finmonitor {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.json;
    requires java.net.http;
    requires org.apache.commons.configuration2;
    requires org.apache.logging.log4j;
    requires org.apache.commons.collections4;
    requires org.apache.httpcomponents.core5.httpcore5;
    requires org.apache.httpcomponents.client5.httpclient5;

    opens org.kr.stocksmonitor to javafx.fxml;
    exports org.kr.stocksmonitor;
    exports org.kr.stocksmonitor.config;
    opens org.kr.stocksmonitor.config to javafx.fxml;
    exports org.kr.stocksmonitor.yahoo;
    opens org.kr.stocksmonitor.yahoo to javafx.fxml;
}