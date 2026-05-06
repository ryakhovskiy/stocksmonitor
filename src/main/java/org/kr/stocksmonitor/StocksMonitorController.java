package org.kr.stocksmonitor;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import java.net.*;

import javafx.util.StringConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kr.stocksmonitor.config.ConfigManager;
import org.kr.stocksmonitor.utils.LogUtils;
import org.kr.stocksmonitor.yahoo.NewsItem;
import org.kr.stocksmonitor.yahoo.QuoteItem;
import org.kr.stocksmonitor.yahoo.YahooAPI;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

public class StocksMonitorController implements Initializable {

    private static final Logger log = LogManager.getLogger(StocksMonitorController.class);
    protected static final ExecutorService executorService = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "stocksmonitor-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stocksmonitor-debounce");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<ScheduledFuture<?>> pendingSearch = new AtomicReference<>();
    private final AtomicBoolean suppressQuoteAction = new AtomicBoolean(false);
    private final AtomicLong newsRequestId = new AtomicLong();
    private static final DateTimeFormatter NEWS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    public DatePicker yahooStartDatePicker;
    public DatePicker yahooEndDatePicker;
    public Slider yahooDateRangeSlider;
    public Label yahooDateRangeLabel;
    public TableView<QuoteItem> yahooTableFavoriteQuotes;

    protected HostServices hostServices;

    protected void injectHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        log.debug("initializing the controller...");
        initYahoooSymbolsCombobox();
        initYahoooTableView();
        initDatePickers();
    }

    private void initDatePickers() {
        yahooDateRangeSlider.setValue(1);
        setDatePickersBasedOnSlider();
        yahooDateRangeSlider.valueProperty().addListener((_, _, _) -> setDatePickersBasedOnSlider());
    }

    private void initYahoooTableView() {
        yahooTableFavoriteQuotes.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems().addListener((ListChangeListener<QuoteItem>) change -> {
            List<QuoteItem> selectedQuotes = (List<QuoteItem>)change.getList();
            handleSelectedQuotes(selectedQuotes);
        });

        loadFavoriteQuotes();
        tabPaneData.getSelectionModel().selectedItemProperty().addListener((_, _, newValue) -> {
            if (newValue.equals(tabNews)) handleTabNewsSelection();
        });
        setYahooTableFavoriteQuotesColumns();
    }

    private void setYahooTableFavoriteQuotesColumns() {
        TableColumn<QuoteItem, String> typeDispColumn = new TableColumn<>("Type");
        typeDispColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getTypeDisp()));
        typeDispColumn.setPrefWidth(60);

        TableColumn<QuoteItem, String> symbolColumn = new TableColumn<>("Symbol");
        symbolColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getSymbol()));
        symbolColumn.setPrefWidth(60);

        TableColumn<QuoteItem, String> shortnameColumn = new TableColumn<>("Short Name");
        shortnameColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getShortname()));
        shortnameColumn.setPrefWidth(220);

        TableColumn<QuoteItem, String> longnameColumn = new TableColumn<>("Long Name");
        longnameColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getLongname()));
        longnameColumn.setPrefWidth(300);

        TableColumn<QuoteItem, String> quoteTypeColumn = new TableColumn<>("Type");
        quoteTypeColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getQuoteType()));
        quoteTypeColumn.setPrefWidth(80);

        TableColumn<QuoteItem, String> exchangeColumn = new TableColumn<>("Exch");
        exchangeColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getExchange()));
        exchangeColumn.setPrefWidth(60);

        TableColumn<QuoteItem, String> exchDispColumn = new TableColumn<>("Exchange");
        exchDispColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getExchDisp()));
        exchDispColumn.setPrefWidth(90);

        TableColumn<QuoteItem, String> indexColumn = new TableColumn<>("Index");
        indexColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getIndex()));
        indexColumn.setPrefWidth(0);

        TableColumn<QuoteItem, Boolean> isYahooFinanceColumn = new TableColumn<>("Yahoo");
        isYahooFinanceColumn.setCellValueFactory(data -> Bindings.createBooleanBinding(() ->
                data.getValue().isYahooFinance()));
        isYahooFinanceColumn.setPrefWidth(0);

        yahooTableFavoriteQuotes.getColumns().clear();
        yahooTableFavoriteQuotes.getColumns().addAll(typeDispColumn, symbolColumn, shortnameColumn, longnameColumn,
                quoteTypeColumn, exchangeColumn, exchDispColumn, indexColumn, isYahooFinanceColumn);
    }

    private void handleSelectedQuotes(List<QuoteItem> selectedQuotes) {
        log.debug(selectedQuotes);
        if (tabPaneData.getSelectionModel().getSelectedItem() == tabNews) {
            loadNewsForSelection(selectedQuotes);
        }
    }

    private void handleTabNewsSelection() {
        log.debug("handleTabNewsSelection()");
        loadNewsForSelection(yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems());
    }

    private void loadNewsForSelection(List<QuoteItem> selected) {
        if (selected == null || selected.isEmpty()) {
            showNewsMessage("Select a ticker to see news");
            return;
        }
        // Snapshot the symbols — selectedItems is observable and may mutate before the worker runs.
        final List<String> symbols = selected.stream()
                .map(QuoteItem::getSymbol)
                .filter(s -> s != null && !s.isEmpty())
                .distinct()
                .toList();
        if (symbols.isEmpty()) {
            showNewsMessage("Select a ticker to see news");
            return;
        }
        showNewsMessage("Loading news...");
        final long requestId = newsRequestId.incrementAndGet();
        executorService.submit(() -> {
            Instant start = Instant.now();
            // LinkedHashMap dedups by uuid while preserving insertion order across symbols.
            Map<String, NewsItem> dedup = new LinkedHashMap<>();
            for (String symbol : symbols) {
                try {
                    for (NewsItem item : YahooAPI.getInstance().getNews(symbol)) {
                        if (!item.uuid().isEmpty()) dedup.putIfAbsent(item.uuid(), item);
                        else dedup.putIfAbsent(item.link(), item);
                    }
                } catch (IOException e) {
                    log.error("Error fetching news for {}", symbol, e);
                }
            }
            List<NewsItem> news = new ArrayList<>(dedup.values());
            news.sort(Comparator.comparing(NewsItem::publishTime).reversed());
            Platform.runLater(() -> {
                if (requestId != newsRequestId.get()) return; // a newer request superseded us
                renderNews(news);
            });
            LogUtils.debugDuration(log, start, "loading yahoo news");
        });
    }

    private void showNewsMessage(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14;");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMaxHeight(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        tabNews.setContent(new StackPane(label));
    }

    private void renderNews(List<NewsItem> news) {
        if (news.isEmpty()) {
            showNewsMessage("No news found");
            return;
        }

        TableView<NewsItem> table = new TableView<>();

        TableColumn<NewsItem, String> dateCol = new TableColumn<>("Published");
        dateCol.setCellValueFactory(d -> Bindings.createStringBinding(() -> formatPublishTime(d.getValue().publishTime())));
        dateCol.setPrefWidth(140);

        TableColumn<NewsItem, String> publisherCol = new TableColumn<>("Publisher");
        publisherCol.setCellValueFactory(d -> Bindings.createStringBinding(() -> d.getValue().publisher()));
        publisherCol.setPrefWidth(160);

        TableColumn<NewsItem, NewsItem> titleCol = new TableColumn<>("Title");
        titleCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        titleCol.setCellFactory(_ -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();
            {
                link.setOnAction(_ -> {
                    NewsItem item = getItem();
                    if (item != null && hostServices != null && !item.link().isEmpty()) {
                        hostServices.showDocument(item.link());
                    }
                });
                link.setWrapText(true);
            }

            @Override
            protected void updateItem(NewsItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    link.setText(item.title());
                    setGraphic(link);
                    setWrapText(true);
                }
            }
        });
        titleCol.setPrefWidth(560);

        TableColumn<NewsItem, String> tickersCol = new TableColumn<>("Tickers");
        tickersCol.setCellValueFactory(d -> Bindings.createStringBinding(() -> String.join(", ", d.getValue().relatedTickers())));
        tickersCol.setPrefWidth(120);

        table.getColumns().addAll(dateCol, publisherCol, titleCol, tickersCol);
        table.getItems().setAll(news);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        tabNews.setContent(new StackPane(table));
    }

    private static String formatPublishTime(Instant t) {
        if (t == null || t.equals(Instant.EPOCH)) return "";
        return NEWS_DATE_FORMAT.format(t);
    }

    private void loadFavoriteQuotes() {
        log.debug("loading favorite quotes...");
        List<QuoteItem> favQuotes = ConfigManager.getInstance().readFavoriteQuotes();
        if (favQuotes.isEmpty()) return;
        log.debug("adding favorite quotes to the table: {}", favQuotes);
        yahooTableFavoriteQuotes.getItems().addAll(favQuotes);
    }

    private void initYahoooSymbolsCombobox() {
        ComboBoxListViewSkin<QuoteItem> skin = new ComboBoxListViewSkin<>(cbxYahooQuote);
        skin.getPopupContent().addEventFilter(KeyEvent.ANY, e -> {
            if (e.getCode() == KeyCode.UNDEFINED) {
                log.debug("initYahoooTickersCombobox() {}", e);
                return;
            }
            if (e.getCode() == KeyCode.SPACE) {
                log.debug(e.getText());
                e.consume();
            }
        });
        cbxYahooQuote.setSkin(skin);
        cbxYahooQuote.setEditable(true);

        cbxYahooQuote.getEditor().textProperty().addListener((_, _, newValue) -> reloadYahooTickerCombobox(newValue));
        cbxYahooQuote.setOnAction(_ -> {
            if (suppressQuoteAction.get()) return;
            // Only treat this as a real selection if the dropdown is open (user picked from the list).
            if (!cbxYahooQuote.isShowing()) return;
            final Object value = cbxYahooQuote.getSelectionModel().getSelectedItem();
            log.debug("selected: {}", value);
            if (!(value instanceof QuoteItem selected))
                return;
            var items = yahooTableFavoriteQuotes.getItems();
            if (items.contains(selected)) return;
            items.add(selected);
        });
        executorService.submit(() -> reloadYahooTickerCombobox("ACN"));
    }

    private void reloadYahooTickerCombobox(String value) {
        if (value.isEmpty())
            return;
        log.debug("reloading yahoo quotes data: {}", value);
        ScheduledFuture<?> prev = pendingSearch.getAndSet(null);
        if (prev != null) prev.cancel(false);
        ScheduledFuture<?> future = debounceExecutor.schedule(() -> {
            Instant start = Instant.now();
            try {
                List<QuoteItem> quotes = YahooAPI.getInstance().getQuotes(value);
                Platform.runLater(() -> reloadYahooQuotesCombobox(quotes));
            } catch (Exception e) {
                log.error("Error while reloading the yahoo quotes", e);
            } finally {
                LogUtils.debugDuration(log, start, "reloading yahoo quotes");
            }
        }, 300, TimeUnit.MILLISECONDS);
        pendingSearch.set(future);
    }

    private void reloadYahooQuotesCombobox(List<QuoteItem> quotes) {
        if (quotes.isEmpty()) return;
        log.debug("reloading yahoo quotes: {}", quotes);
        final ObservableList<QuoteItem> observableList = FXCollections.observableArrayList(quotes);
        final FilteredList<QuoteItem> filteredData = new FilteredList<>(observableList);

        UnaryOperator<TextFormatter.Change> filter = change -> {
            if (null == change) return null;
            if (0 == change.getSelection().getLength()) return change;
            if (change.getSelection().getLength() == change.getSelection().getEnd()) return change; //everything selected
            if (change.getSelection().getStart() == 0 && change.getSelection().getEnd() == 0) return change;
            final String newText = change.getControlNewText();
            if (newText.isEmpty()) {
                cbxYahooQuote.hide();
            } else {
                filteredData.setPredicate(quoteItem ->
                        quoteItem.getShortname().toLowerCase().contains(newText.toLowerCase()) ||
                                quoteItem.getSymbol().toLowerCase().contains(newText.toLowerCase()));
                cbxYahooQuote.show();
            }
            return change;
        };
        final TextFormatter<String> textFormatter = new TextFormatter<>(filter);
        cbxYahooQuote.setEditable(true);
        cbxYahooQuote.getEditor().setTextFormatter(textFormatter);

        // Set up string converter for correct display
        cbxYahooQuote.setConverter(new StringConverter<>() {
            @Override
            public String toString(QuoteItem o) {
                if (o == null) return null;
                return String.format("%s | %s | %s | Exch: %s",
                        o.getQuoteType(), o.getSymbol(), o.getShortname(), o.getExchDisp());
            }

            @Override
            public QuoteItem fromString(String string) { return null; }
        });

        // Suppress the action handler while we swap items — setItems can clear/change the value
        // and would otherwise fire setOnAction with a stale selection.
        suppressQuoteAction.set(true);
        try {
            cbxYahooQuote.setItems(filteredData);
            cbxYahooQuote.hide();
            cbxYahooQuote.setVisibleRowCount(filteredData.size());
            cbxYahooQuote.show();
        } finally {
            suppressQuoteAction.set(false);
        }
    }

    public void shutdown() throws IOException {
        log.debug("shutting down the controller");
        debounceExecutor.shutdownNow();
        executorService.shutdownNow();
        ConfigManager.getInstance().saveFavoriteQuotes(yahooTableFavoriteQuotes.getItems());
    }

    private void setDatePickersBasedOnSlider() {
        applyDateRange((int) yahooDateRangeSlider.getValue(), yahooDateRangeLabel, yahooStartDatePicker, yahooEndDatePicker);
    }

    static void applyDateRange(int value, Label label, DatePicker start, DatePicker end) {
        LocalDate today = LocalDate.now();
        switch (value) {
            case 1:  label.setText("1 day");    start.setValue(today.minusDays(1));    break;
            case 2:  label.setText("2 days");   start.setValue(today.minusDays(2));    break;
            case 3:  label.setText("3 days");   start.setValue(today.minusDays(3));    break;
            case 4:  label.setText("1 week");   start.setValue(today.minusDays(7));    break;
            case 5:  label.setText("2 weeks");  start.setValue(today.minusDays(14));   break;
            case 6:  label.setText("1 month");  start.setValue(today.minusMonths(1));  break;
            case 7:  label.setText("2 months"); start.setValue(today.minusMonths(2));  break;
            case 8:  label.setText("3 months"); start.setValue(today.minusMonths(3));  break;
            case 9:  label.setText("6 months"); start.setValue(today.minusMonths(6));  break;
            case 10: label.setText("1 year");   start.setValue(today.minusYears(1));   break;
            case 11: label.setText("2 years");  start.setValue(today.minusYears(2));   break;
            case 12: label.setText("3 years");  start.setValue(today.minusYears(3));   break;
            case 13: label.setText("5 years");  start.setValue(today.minusYears(5));   break;
            case 14: label.setText("10 years"); start.setValue(today.minusYears(10));  break;
            case 15: label.setText("MAX");      start.setValue(today.minusYears(200)); break;
            default: break;
        }
        end.setValue(today);
    }

    @FXML protected Tab tabNews;
    @FXML protected TabPane tabPaneData;
    @FXML protected ComboBox<QuoteItem> cbxYahooQuote;
}
