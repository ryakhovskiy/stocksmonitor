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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.net.*;

import javafx.util.StringConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kr.stocksmonitor.config.ConfigManager;
import org.kr.stocksmonitor.utils.LogUtils;
import org.kr.stocksmonitor.yahoo.Dividend;
import org.kr.stocksmonitor.yahoo.HistoricalBar;
import org.kr.stocksmonitor.yahoo.NewsItem;
import org.kr.stocksmonitor.yahoo.QuoteItem;
import org.kr.stocksmonitor.yahoo.QuoteSnapshot;
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
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class StocksMonitorController implements Initializable {

    private static final Logger log = LogManager.getLogger(StocksMonitorController.class);
    protected final ExecutorService executorService = Executors.newFixedThreadPool(16, r -> {
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
    private final AtomicReference<ScheduledFuture<?>> pendingDateReload = new AtomicReference<>();
    private final AtomicBoolean suppressQuoteAction = new AtomicBoolean(false);
    private final AtomicLong newsRequestId = new AtomicLong();
    private final AtomicLong dataRequestId = new AtomicLong();
    private final AtomicLong historyRequestId = new AtomicLong();
    private final AtomicLong chartRequestId = new AtomicLong();
    private final AtomicLong dividendRequestId = new AtomicLong();

    public enum TargetCurrency { Native, USD, EUR }
    private TargetCurrency targetCurrency = TargetCurrency.Native;
    private static final int DEFAULT_HISTORY_DAYS = 365;
    private static final int DEFAULT_SLIDER_POSITION = 10; // matches "1 year" in applyDateRange
    // While restoring persisted UI state we want listeners to NOT trigger a re-save round trip.
    private final AtomicBoolean restoringSettings = new AtomicBoolean(false);
    // Slider drags fire many events — coalesce settings writes to avoid disk thrash.
    // Keys accumulate into pendingSettings; a single debounced flush writes all dirty keys at once.
    private final Map<String, Object> pendingSettings = new ConcurrentHashMap<>();
    private final AtomicReference<ScheduledFuture<?>> pendingSettingsFlush = new AtomicReference<>();
    private static final DateTimeFormatter NEWS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter HIST_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Chart colour scheme — palette cycled per ticker so each line is distinct.
    private static final String[] CHART_COLORS = {
            "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
            "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf"
    };
    private static String chartColor(int idx) {
        return CHART_COLORS[Math.floorMod(idx, CHART_COLORS.length)];
    }
    private static final String DIV_DOT_DEFAULT     = "-fx-background-color: #28a745, white; -fx-background-insets: 0, 2; -fx-background-radius: 5px; -fx-padding: 5px;";
    private static final String DIV_DOT_HOVER       = "-fx-background-color: #28a745, white; -fx-background-insets: 0, 2; -fx-background-radius: 7px; -fx-padding: 7px;";
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
        ConfigManager.AppSettings settings = ConfigManager.getInstance().readSettings();
        // Restore in a window where listeners are no-ops, so we don't echo the loaded value back to disk.
        restoringSettings.set(true);
        try {
            initYahoooSymbolsCombobox(settings);
            initYahoooTableView();
            initDatePickers(settings);
            initCurrencyCombobox(settings);
            initToolbarButtons();
            restoreSelectedSymbols(settings);
            restoreLastTab(settings);
        } finally {
            restoringSettings.set(false);
        }
        // After restoring, kick the active tab to load data for the restored selection.
        // We do this on a fresh runLater so the favorites table's selection model has fully settled.
        Platform.runLater(this::refreshCurrentTab);
    }

    private void restoreSelectedSymbols(ConfigManager.AppSettings settings) {
        if (settings == null) return;
        List<String> wanted = settings.selectedSymbols();
        if (wanted == null || wanted.isEmpty()) return;
        ObservableList<QuoteItem> items = yahooTableFavoriteQuotes.getItems();
        Set<String> wantedSet = new HashSet<>(wanted);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            QuoteItem q = items.get(i);
            if (q != null && q.getSymbol() != null && wantedSet.contains(q.getSymbol())) indices.add(i);
        }
        if (indices.isEmpty()) return;
        var selModel = yahooTableFavoriteQuotes.getSelectionModel();
        // selectIndices(int, int...) needs the first index plus a varargs tail.
        int first = indices.get(0);
        int[] rest = new int[indices.size() - 1];
        for (int i = 1; i < indices.size(); i++) rest[i - 1] = indices.get(i);
        selModel.selectIndices(first, rest);
    }

    private void restoreLastTab(ConfigManager.AppSettings settings) {
        if (settings == null || settings.lastTab() == null || settings.lastTab().isEmpty()) return;
        for (Tab t : tabPaneData.getTabs()) {
            if (settings.lastTab().equals(t.getText())) {
                tabPaneData.getSelectionModel().select(t);
                return;
            }
        }
    }

    private void initToolbarButtons() {
        if (btnRefresh != null) btnRefresh.setOnAction(_ -> forceRefreshActiveTab());
        if (btnRemove != null) btnRemove.setOnAction(_ -> removeSelectedFavorites());
    }

    /** User-initiated refresh: invalidate cached data for selected symbols, then reload the visible tab. */
    private void forceRefreshActiveTab() {
        List<QuoteItem> selected = yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems();
        for (QuoteItem q : selected) {
            if (q != null && q.getSymbol() != null) YahooAPI.getInstance().invalidate(q.getSymbol());
        }
        handleSelectedQuotes(selected);
    }

    private void initCurrencyCombobox(ConfigManager.AppSettings settings) {
        cbxCurrency.setItems(FXCollections.observableArrayList(TargetCurrency.values()));
        TargetCurrency initial = TargetCurrency.Native;
        if (settings != null && settings.currency() != null && !settings.currency().isEmpty()) {
            try { initial = TargetCurrency.valueOf(settings.currency()); }
            catch (IllegalArgumentException ignore) { /* persisted value no longer valid — keep default */ }
        }
        targetCurrency = initial;
        cbxCurrency.getSelectionModel().select(initial);
        cbxCurrency.valueProperty().addListener((_, _, newValue) -> {
            if (newValue == null) return;
            targetCurrency = newValue;
            if (!restoringSettings.get()) persistSettingAsync(ConfigManager.KEY_CURRENCY, newValue.name());
            refreshCurrentTab();
        });
    }

    private void refreshCurrentTab() {
        handleSelectedQuotes(yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems());
    }

    private void initDatePickers(ConfigManager.AppSettings settings) {
        // Set values BEFORE attaching listeners so the slider listener doesn't override the explicit bounds.
        LocalDate today = LocalDate.now();
        LocalDate start = (settings != null && settings.startDate() != null)
                ? settings.startDate() : today.minusDays(DEFAULT_HISTORY_DAYS);
        LocalDate end = (settings != null && settings.endDate() != null)
                ? settings.endDate() : today;
        int sliderPos = (settings != null && settings.sliderValue() > 0)
                ? settings.sliderValue() : DEFAULT_SLIDER_POSITION;
        yahooStartDatePicker.setValue(start);
        yahooEndDatePicker.setValue(end);
        yahooDateRangeSlider.setValue(sliderPos);
        applyDateRangeLabel(sliderPos, yahooDateRangeLabel);
        yahooDateRangeSlider.valueProperty().addListener((_, _, _) -> {
            setDatePickersBasedOnSlider();
            if (!restoringSettings.get()) persistSettingAsync(ConfigManager.KEY_SLIDER_VALUE, (int) yahooDateRangeSlider.getValue());
        });
        yahooStartDatePicker.valueProperty().addListener((_, _, newValue) -> {
            if (!restoringSettings.get() && newValue != null)
                persistSettingAsync(ConfigManager.KEY_START_DATE, newValue.toString());
            scheduleDateDependentReload();
        });
        yahooEndDatePicker.valueProperty().addListener((_, _, newValue) -> {
            if (!restoringSettings.get() && newValue != null)
                persistSettingAsync(ConfigManager.KEY_END_DATE, newValue.toString());
            scheduleDateDependentReload();
        });
    }

    private void initYahoooTableView() {
        yahooTableFavoriteQuotes.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems().addListener((ListChangeListener<QuoteItem>) change -> {
            List<QuoteItem> selectedQuotes = (List<QuoteItem>) change.getList();
            if (!restoringSettings.get()) {
                persistSettingAsync(ConfigManager.KEY_SELECTED_SYMBOLS, symbolsOf(selectedQuotes));
            }
            handleSelectedQuotes(selectedQuotes);
        });

        // Remove via context menu or Delete key.
        MenuItem removeItem = new MenuItem("Remove");
        removeItem.setOnAction(_ -> removeSelectedFavorites());
        yahooTableFavoriteQuotes.setContextMenu(new ContextMenu(removeItem));
        yahooTableFavoriteQuotes.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                removeSelectedFavorites();
                e.consume();
            }
        });

        loadFavoriteQuotes();
        // Persist favorites on every add/remove — don't wait for shutdown.
        yahooTableFavoriteQuotes.getItems().addListener((ListChangeListener<QuoteItem>) c -> {
            if (restoringSettings.get()) return;
            persistFavoritesAsync();
        });
        tabPaneData.getSelectionModel().selectedItemProperty().addListener((_, _, newValue) -> {
            if (newValue == null) return;
            if (!restoringSettings.get())
                persistSettingAsync(ConfigManager.KEY_LAST_TAB, newValue.getText());
            if (newValue == tabNews) handleTabNewsSelection();
            else if (newValue == tabData) handleTabDataSelection();
            else if (newValue == tabHistory) handleTabHistorySelection();
            else if (newValue == tabChart) handleTabChartSelection();
            else if (newValue == tabDividend) handleTabDividendSelection();
        });
        setYahooTableFavoriteQuotesColumns();
        yahooTableFavoriteQuotes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
    }

    private void persistFavoritesAsync() {
        // Snapshot the items on the FX thread; write off-thread.
        List<QuoteItem> snapshot = new ArrayList<>(yahooTableFavoriteQuotes.getItems());
        executorService.submit(() -> ConfigManager.getInstance().saveFavoriteQuotes(snapshot));
    }

    private void removeSelectedFavorites() {
        // Copy first — getSelectedItems is observable and removeAll mutates it as we go.
        List<QuoteItem> toRemove = new ArrayList<>(yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems());
        if (toRemove.isEmpty()) return;
        yahooTableFavoriteQuotes.getItems().removeAll(toRemove);
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

        TableColumn<QuoteItem, String> assetClassColumn = new TableColumn<>("Asset Class");
        assetClassColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getQuoteType()));
        assetClassColumn.setPrefWidth(90);

        TableColumn<QuoteItem, String> exchangeColumn = new TableColumn<>("Exch");
        exchangeColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getExchange()));
        exchangeColumn.setPrefWidth(60);

        TableColumn<QuoteItem, String> exchDispColumn = new TableColumn<>("Exchange");
        exchDispColumn.setCellValueFactory(data -> Bindings.createStringBinding(() ->
                data.getValue().getExchDisp()));
        exchDispColumn.setPrefWidth(90);

        yahooTableFavoriteQuotes.getColumns().clear();
        yahooTableFavoriteQuotes.getColumns().addAll(typeDispColumn, symbolColumn, shortnameColumn, longnameColumn,
                assetClassColumn, exchangeColumn, exchDispColumn);
    }

    private void handleSelectedQuotes(List<QuoteItem> selectedQuotes) {
        log.debug(selectedQuotes);
        Tab active = tabPaneData.getSelectionModel().getSelectedItem();
        if (active == tabNews) loadNewsForSelection(selectedQuotes);
        else if (active == tabData) loadDataForSelection(selectedQuotes);
        else if (active == tabHistory) loadHistoryForSelection(selectedQuotes);
        else if (active == tabChart) loadChartForSelection(selectedQuotes);
        else if (active == tabDividend) loadDividendForSelection(selectedQuotes);
    }

    private void handleTabNewsSelection() {
        log.debug("handleTabNewsSelection()");
        loadNewsForSelection(yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems());
    }

    private void handleTabDataSelection() {
        log.debug("handleTabDataSelection()");
        loadDataForSelection(yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems());
    }

    private void handleTabHistorySelection() {
        log.debug("handleTabHistorySelection()");
        loadHistoryForSelection(yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems());
    }

    private void handleTabChartSelection() {
        log.debug("handleTabChartSelection()");
        loadChartForSelection(yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems());
    }

    private void handleTabDividendSelection() {
        log.debug("handleTabDividendSelection()");
        loadDividendForSelection(yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems());
    }

    private void scheduleDateDependentReload() {
        ScheduledFuture<?> prev = pendingDateReload.getAndSet(null);
        if (prev != null) prev.cancel(false);
        ScheduledFuture<?> future = debounceExecutor.schedule(
                () -> Platform.runLater(() -> {
                    Tab active = tabPaneData.getSelectionModel().getSelectedItem();
                    List<QuoteItem> selected = yahooTableFavoriteQuotes.getSelectionModel().getSelectedItems();
                    if (active == tabHistory) loadHistoryForSelection(selected);
                    else if (active == tabChart) loadChartForSelection(selected);
                    else if (active == tabDividend) loadDividendForSelection(selected);
                }),
                250, TimeUnit.MILLISECONDS);
        pendingDateReload.set(future);
    }

    private void loadDataForSelection(List<QuoteItem> selected) {
        final List<String> symbols = symbolsOf(selected);
        if (symbols.isEmpty()) {
            showTabMessage(tabData, "Select a ticker to see today's data");
            return;
        }
        showTabMessage(tabData, "Loading data...");
        final long requestId = dataRequestId.incrementAndGet();
        executorService.submit(() -> {
            Instant start = Instant.now();
            List<QuoteSnapshot> snapshots = new ArrayList<>(symbols.size());
            for (String symbol : symbols) {
                try {
                    QuoteSnapshot snap = YahooAPI.getInstance().getSnapshot(symbol);
                    if (snap != null) snapshots.add(snap);
                } catch (IOException e) {
                    log.error("Error fetching snapshot for {}", symbol, e);
                }
            }
            // Snapshot is "now" data; pull a small recent FX window so floorEntry can resolve weekend dates.
            LocalDate today = LocalDate.now();
            LocalDate fxFrom = today.minusDays(7);
            Set<String> currencies = snapshots.stream().map(QuoteSnapshot::currency).collect(Collectors.toSet());
            FxConverter converter = buildConverter(currencies, fxFrom, today);
            Platform.runLater(() -> {
                if (requestId != dataRequestId.get()) return;
                renderData(snapshots, converter);
            });
            LogUtils.debugDuration(log, start, "loading yahoo snapshots");
        });
    }

    private void loadChartForSelection(List<QuoteItem> selected) {
        final List<String> symbols = symbolsOf(selected);
        if (symbols.isEmpty()) {
            showTabMessage(tabChart, "Select a ticker to see the chart");
            return;
        }
        // Snapshot long names from the QuoteItems so the renderer can label legend rows.
        final Map<String, String> longNames = new LinkedHashMap<>();
        for (QuoteItem q : selected) {
            if (q == null || q.getSymbol() == null) continue;
            String ln = q.getLongname();
            if (ln == null || ln.isEmpty()) ln = q.getShortname();
            longNames.put(q.getSymbol(), ln == null ? "" : ln);
        }
        final LocalDate from = yahooStartDatePicker.getValue();
        final LocalDate to = yahooEndDatePicker.getValue();
        if (from == null || to == null || from.isAfter(to)) {
            showTabMessage(tabChart, "Pick a valid start/end date");
            return;
        }
        showTabMessage(tabChart, "Loading chart...");
        final long requestId = chartRequestId.incrementAndGet();
        executorService.submit(() -> {
            Instant start = Instant.now();
            LinkedHashMap<String, List<HistoricalBar>> bySymbol = new LinkedHashMap<>();
            LinkedHashMap<String, List<Dividend>> divsBySymbol = new LinkedHashMap<>();
            for (String symbol : symbols) {
                try {
                    bySymbol.put(symbol, YahooAPI.getInstance().getHistory(symbol, from, to));
                } catch (IOException e) {
                    log.error("Error fetching history for {} {}..{}", symbol, from, to, e);
                }
                try {
                    divsBySymbol.put(symbol, YahooAPI.getInstance().getDividends(symbol, from, to));
                } catch (IOException e) {
                    log.error("Error fetching dividends for {} {}..{}", symbol, from, to, e);
                    divsBySymbol.put(symbol, List.of());
                }
            }
            Set<String> currencies = bySymbol.values().stream()
                    .flatMap(List::stream)
                    .map(HistoricalBar::currency)
                    .collect(Collectors.toSet());
            FxConverter converter = buildConverter(currencies, from, to);
            Platform.runLater(() -> {
                if (requestId != chartRequestId.get()) return;
                renderChart(bySymbol, divsBySymbol, converter, longNames);
            });
            LogUtils.debugDuration(log, start, "loading yahoo chart");
        });
    }

    private void renderChart(Map<String, List<HistoricalBar>> bySymbol,
                             Map<String, List<Dividend>> divsBySymbol,
                             FxConverter conv,
                             Map<String, String> longNames) {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Date");
        xAxis.setForceZeroInRange(false);
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number n) {
                return LocalDate.ofEpochDay(n.longValue()).format(HIST_DATE_FORMAT);
            }
            @Override public Number fromString(String s) { return null; }
        });
        NumberAxis yAxis = new NumberAxis();
        // Y-axis label: in Native mode show "Close" or "Close (mixed)" if we have >1 native currency.
        Set<String> nativeCcys = bySymbol.values().stream()
                .flatMap(List::stream)
                .map(HistoricalBar::currency)
                .filter(c -> c != null && !c.isEmpty())
                .collect(Collectors.toSet());
        String yLabel;
        if (conv != null) yLabel = "Close (" + conv.target() + ")";
        else if (nativeCcys.size() == 1) yLabel = "Close (" + nativeCcys.iterator().next() + ")";
        else if (nativeCcys.size() > 1) yLabel = "Close (mixed)";
        else yLabel = "Close";
        yAxis.setLabel(yLabel);
        yAxis.setForceZeroInRange(false);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Close price");
        // Symbols must be created so each data point has a Node to attach a tooltip to.
        chart.setCreateSymbols(true);
        // We render our own colour-matched legend below the chart, so suppress the built-in.
        chart.setLegendVisible(false);
        chart.setAnimated(false);

        // Per-symbol map of converted close prices, used to position dividend points on the price line.
        Map<String, NavigableMap<LocalDate, Double>> closesBySymbol = new HashMap<>();
        Map<String, NavigableMap<LocalDate, Double>> nativeClosesBySymbol = new HashMap<>();
        Map<String, String> colorBySymbol = new LinkedHashMap<>();

        int colorIdx = 0;
        for (Map.Entry<String, List<HistoricalBar>> entry : bySymbol.entrySet()) {
            String ticker = entry.getKey();
            String color = chartColor(colorIdx++);
            colorBySymbol.put(ticker, color);

            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            // Use the symbol as the series name (so tooltip header reads cleanly).
            // In Native mode with mixed currencies, append the per-series currency.
            String seriesName = ticker;
            String longName = longNames == null ? null : longNames.get(ticker);
            if (longName != null && !longName.isEmpty()) seriesName = ticker + " — " + longName;
            if (conv == null && nativeCcys.size() > 1 && !entry.getValue().isEmpty()) {
                String c = entry.getValue().get(0).currency();
                if (c != null && !c.isEmpty()) seriesName = seriesName + " (" + c + ")";
            }
            series.setName(seriesName);
            NavigableMap<LocalDate, Double> closes = new TreeMap<>();
            NavigableMap<LocalDate, Double> nativeCloses = new TreeMap<>();
            for (HistoricalBar bar : entry.getValue()) {
                if (Double.isNaN(bar.close())) continue;
                double y = convertValue(conv, bar.close(), bar.currency(), bar.date());
                series.getData().add(new XYChart.Data<>(bar.date().toEpochDay(), y));
                closes.put(bar.date(), y);
                nativeCloses.put(bar.date(), bar.close());
            }
            closesBySymbol.put(ticker, closes);
            nativeClosesBySymbol.put(ticker, nativeCloses);
            if (series.getData().isEmpty()) continue;
            chart.getData().add(series);
            // Style the price line in this ticker's palette colour.
            String lineStyle = "-fx-stroke: " + color + "; -fx-stroke-width: 1.5px;";
            String dotDefault = "-fx-background-color: " + color + "; -fx-background-radius: 2px; -fx-padding: 2px;";
            String dotHover = "-fx-background-color: " + color + "; -fx-background-radius: 5px; -fx-padding: 5px;";
            Node priceLineNode = series.getNode();
            if (priceLineNode != null) priceLineNode.setStyle(lineStyle);
            // Symbol Nodes are created when the series is added; attach tooltips after.
            attachPointTooltips(series, conv == null ? null : conv.target(), dotDefault, dotHover);
        }

        // Overlay dividend points per ticker: green dots positioned on the price line at each div date.
        for (Map.Entry<String, List<Dividend>> entry : divsBySymbol.entrySet()) {
            String ticker = entry.getKey();
            List<Dividend> dividends = entry.getValue();
            if (dividends == null || dividends.isEmpty()) continue;
            NavigableMap<LocalDate, Double> closes = closesBySymbol.get(ticker);
            NavigableMap<LocalDate, Double> nativeCloses = nativeClosesBySymbol.get(ticker);
            if (closes == null || closes.isEmpty()) continue;

            XYChart.Series<Number, Number> divSeries = new XYChart.Series<>();
            divSeries.setName(ticker + " · dividend");

            // Snapshot the per-point data (date, amount, displayCurrency, yieldPct) for the tooltip
            // attachment phase, since once added to the chart the Data nodes are looked up by index.
            List<DividendPoint> tipData = new ArrayList<>();
            for (Dividend div : dividends) {
                Double yPrice = closes.get(div.date());
                if (yPrice == null) {
                    Map.Entry<LocalDate, Double> e = closes.floorEntry(div.date());
                    if (e == null) e = closes.firstEntry();
                    yPrice = e == null ? null : e.getValue();
                }
                if (yPrice == null) continue;
                Double nPrice = nativeCloses.get(div.date());
                if (nPrice == null) {
                    Map.Entry<LocalDate, Double> e = nativeCloses.floorEntry(div.date());
                    if (e == null) e = nativeCloses.firstEntry();
                    nPrice = e == null ? null : e.getValue();
                }
                double yieldPct = (nPrice == null || nPrice == 0.0 || Double.isNaN(div.amount()))
                        ? Double.NaN
                        : div.amount() / nPrice * 100.0;
                double displayAmount = convertValue(conv, div.amount(), div.currency(), div.date());
                String displayCcy = currencyDisplay(conv, div.currency());

                divSeries.getData().add(new XYChart.Data<>(div.date().toEpochDay(), yPrice));
                tipData.add(new DividendPoint(ticker, div.date(), displayAmount, displayCcy, yieldPct));
            }

            if (divSeries.getData().isEmpty()) continue;
            chart.getData().add(divSeries);
            // Hide the connecting line — we want dots only, not a green zigzag.
            Node lineNode = divSeries.getNode();
            if (lineNode != null) lineNode.setStyle("-fx-stroke: transparent;");
            // Style each data point as a green marker and attach a hover tooltip.
            attachDividendTooltips(divSeries, tipData);
        }

        if (chart.getData().isEmpty()) {
            showTabMessage(tabChart, "No history found for the selected range");
            return;
        }

        // Custom legend below the chart: per-ticker swatch + "SYM | Long Name".
        FlowPane legend = new FlowPane(16, 6);
        legend.setPadding(new Insets(8, 12, 8, 12));
        for (Map.Entry<String, String> e : colorBySymbol.entrySet()) {
            String ticker = e.getKey();
            String color = e.getValue();
            String longName = longNames == null ? null : longNames.get(ticker);
            String labelText = (longName == null || longName.isEmpty()) ? ticker : ticker + " | " + longName;
            Rectangle swatch = new Rectangle(14, 4, Color.web(color));
            Label label = new Label(labelText);
            HBox row = new HBox(6, swatch, label);
            row.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(row);
        }

        VBox container = new VBox(chart, legend);
        VBox.setVgrow(chart, Priority.ALWAYS);
        tabChart.setContent(container);
    }

    private record DividendPoint(String symbol, LocalDate date, double displayAmount,
                                 String displayCurrency, double yieldPct) {}

    private static void attachDividendTooltips(XYChart.Series<Number, Number> series,
                                               List<DividendPoint> tipData) {
        var data = series.getData();
        for (int i = 0; i < data.size() && i < tipData.size(); i++) {
            Node node = data.get(i).getNode();
            if (node == null) continue;
            DividendPoint p = tipData.get(i);
            String amountStr = Double.isNaN(p.displayAmount())
                    ? ""
                    : String.format(Locale.ROOT, "%,.4f %s", p.displayAmount(),
                            p.displayCurrency() == null ? "" : p.displayCurrency()).trim();
            String yieldStr = Double.isNaN(p.yieldPct())
                    ? ""
                    : String.format(Locale.ROOT, "%.3f%%", p.yieldPct());
            String tipText = String.format("%s%nDividend%n%s%nAmount: %s%nYield:  %s",
                    p.symbol(),
                    p.date().format(HIST_DATE_FORMAT),
                    amountStr,
                    yieldStr);
            Tooltip tip = new Tooltip(tipText);
            tip.setShowDelay(Duration.millis(50));
            tip.setHideDelay(Duration.millis(200));
            Tooltip.install(node, tip);
            node.setCursor(Cursor.HAND);
            // Default styling: solid red marker with white inner ring (10px diameter).
            String defaultStyle = DIV_DOT_DEFAULT;
            String hoverStyle   = DIV_DOT_HOVER;
            node.setStyle(defaultStyle);
            node.setOnMouseEntered(_ -> node.setStyle(hoverStyle));
            node.setOnMouseExited(_ -> node.setStyle(defaultStyle));
        }
    }

    private static void attachPointTooltips(XYChart.Series<Number, Number> series, String currencySuffix,
                                            String dotDefault, String dotHover) {
        final String header = series.getName();
        final String suffix = (currencySuffix == null || currencySuffix.isEmpty()) ? "" : " " + currencySuffix;
        for (XYChart.Data<Number, Number> data : series.getData()) {
            Node node = data.getNode();
            if (node == null) continue;
            LocalDate date = LocalDate.ofEpochDay(data.getXValue().longValue());
            double price = data.getYValue().doubleValue();
            String tipText = String.format("%s%n%s%nClose: %s%s",
                    header,
                    date.format(HIST_DATE_FORMAT),
                    String.format(Locale.ROOT, "%,.2f", price),
                    suffix);
            Tooltip tip = new Tooltip(tipText);
            tip.setShowDelay(Duration.millis(50));
            tip.setHideDelay(Duration.millis(200));
            Tooltip.install(node, tip);
            node.setCursor(Cursor.HAND);
            node.setStyle(dotDefault);
            node.setOnMouseEntered(_ -> node.setStyle(dotHover));
            node.setOnMouseExited(_ -> node.setStyle(dotDefault));
        }
    }

    private void loadHistoryForSelection(List<QuoteItem> selected) {
        final List<String> symbols = symbolsOf(selected);
        if (symbols.isEmpty()) {
            showTabMessage(tabHistory, "Select a ticker to see history");
            return;
        }
        final LocalDate from = yahooStartDatePicker.getValue();
        final LocalDate to = yahooEndDatePicker.getValue();
        if (from == null || to == null || from.isAfter(to)) {
            showTabMessage(tabHistory, "Pick a valid start/end date");
            return;
        }
        showTabMessage(tabHistory, "Loading history...");
        final long requestId = historyRequestId.incrementAndGet();
        executorService.submit(() -> {
            Instant start = Instant.now();
            List<HistoricalBar> bars = new ArrayList<>();
            for (String symbol : symbols) {
                try {
                    bars.addAll(YahooAPI.getInstance().getHistory(symbol, from, to));
                } catch (IOException e) {
                    log.error("Error fetching history for {} {}..{}", symbol, from, to, e);
                }
            }
            bars.sort(Comparator.comparing(HistoricalBar::date).reversed()
                    .thenComparing(HistoricalBar::symbol));
            Set<String> currencies = bars.stream().map(HistoricalBar::currency).collect(Collectors.toSet());
            FxConverter converter = buildConverter(currencies, from, to);
            Platform.runLater(() -> {
                if (requestId != historyRequestId.get()) return;
                renderHistory(bars, converter);
            });
            LogUtils.debugDuration(log, start, "loading yahoo history");
        });
    }

    private void loadDividendForSelection(List<QuoteItem> selected) {
        final List<String> symbols = symbolsOf(selected);
        if (symbols.isEmpty()) {
            showTabMessage(tabDividend, "Select a ticker to see dividends");
            return;
        }
        final LocalDate from = yahooStartDatePicker.getValue();
        final LocalDate to = yahooEndDatePicker.getValue();
        if (from == null || to == null || from.isAfter(to)) {
            showTabMessage(tabDividend, "Pick a valid start/end date");
            return;
        }
        showTabMessage(tabDividend, "Loading dividends...");
        final long requestId = dividendRequestId.incrementAndGet();
        executorService.submit(() -> {
            Instant start = Instant.now();
            // Fetch dividends and history per symbol in parallel — they're independent network calls,
            // and the dividend tab needs both (yield = amount / close * 100, FX-invariant).
            List<CompletableFuture<SymbolDividendData>> futures = new ArrayList<>(symbols.size());
            for (String symbol : symbols) {
                CompletableFuture<List<Dividend>> divFut = CompletableFuture.supplyAsync(() -> {
                    try {
                        return YahooAPI.getInstance().getDividends(symbol, from, to);
                    } catch (IOException e) {
                        log.error("Error fetching dividends for {} {}..{}", symbol, from, to, e);
                        return List.of();
                    }
                }, executorService);
                CompletableFuture<NavigableMap<LocalDate, Double>> histFut = CompletableFuture.supplyAsync(() -> {
                    NavigableMap<LocalDate, Double> closes = new TreeMap<>();
                    try {
                        for (HistoricalBar bar : YahooAPI.getInstance().getHistory(symbol, from, to)) {
                            if (!Double.isNaN(bar.close())) closes.put(bar.date(), bar.close());
                        }
                    } catch (IOException e) {
                        log.error("Error fetching history for yield calc {} {}..{}", symbol, from, to, e);
                    }
                    return closes;
                }, executorService);
                futures.add(divFut.thenCombine(histFut, (divs, closes) -> new SymbolDividendData(symbol, divs, closes)));
            }
            List<Dividend> dividends = new ArrayList<>();
            Map<String, NavigableMap<LocalDate, Double>> nativeCloseBySymbol = new HashMap<>();
            for (CompletableFuture<SymbolDividendData> f : futures) {
                try {
                    SymbolDividendData d = f.get();
                    dividends.addAll(d.dividends());
                    nativeCloseBySymbol.put(d.symbol(), d.closes());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException ee) {
                    log.error("Dividend/history fetch failed", ee);
                }
            }
            dividends.sort(Comparator.comparing(Dividend::date).reversed()
                    .thenComparing(Dividend::symbol));
            Set<String> currencies = dividends.stream().map(Dividend::currency).collect(Collectors.toSet());
            FxConverter converter = buildConverter(currencies, from, to);
            Platform.runLater(() -> {
                if (requestId != dividendRequestId.get()) return;
                renderDividend(dividends, converter, nativeCloseBySymbol);
            });
            LogUtils.debugDuration(log, start, "loading yahoo dividends");
        });
    }

    private record SymbolDividendData(String symbol,
                                      List<Dividend> dividends,
                                      NavigableMap<LocalDate, Double> closes) {}

    private void renderDividend(List<Dividend> dividends,
                                FxConverter conv,
                                Map<String, NavigableMap<LocalDate, Double>> nativeCloseBySymbol) {
        if (dividends.isEmpty()) {
            showTabMessage(tabDividend, "No dividends found for the selected range");
            return;
        }
        TableView<Dividend> table = new TableView<>();
        table.getColumns().add(strCol("Symbol", Dividend::symbol, 100));
        table.getColumns().add(strCol("Date",   d -> HIST_DATE_FORMAT.format(d.date()), 120));

        TableColumn<Dividend, String> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(d -> Bindings.createStringBinding(() -> {
            Dividend div = d.getValue();
            double v = convertValue(conv, div.amount(), div.currency(), div.date());
            return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%,.2f", v);
        }));
        amountCol.setPrefWidth(120);
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        table.getColumns().add(amountCol);

        TableColumn<Dividend, String> yieldCol = new TableColumn<>("Yield %");
        yieldCol.setCellValueFactory(d -> Bindings.createStringBinding(() -> {
            Dividend div = d.getValue();
            double y = computeYieldPercent(div, nativeCloseBySymbol);
            return Double.isNaN(y) ? "" : String.format(Locale.ROOT, "%.3f%%", y);
        }));
        yieldCol.setPrefWidth(100);
        yieldCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        table.getColumns().add(yieldCol);

        table.getColumns().add(strCol("Currency", d -> currencyDisplay(conv, d.currency()), 80));

        table.getItems().setAll(dividends);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        tabDividend.setContent(new StackPane(table));
    }

    private static double computeYieldPercent(Dividend div,
                                              Map<String, NavigableMap<LocalDate, Double>> nativeCloseBySymbol) {
        if (Double.isNaN(div.amount())) return Double.NaN;
        NavigableMap<LocalDate, Double> closes = nativeCloseBySymbol.get(div.symbol());
        if (closes == null || closes.isEmpty()) return Double.NaN;
        Double price = closes.get(div.date());
        if (price == null) {
            Map.Entry<LocalDate, Double> e = closes.floorEntry(div.date());
            if (e == null) e = closes.firstEntry();
            price = e == null ? null : e.getValue();
        }
        if (price == null || price == 0.0) return Double.NaN;
        return div.amount() / price * 100.0;
    }

    private static List<String> symbolsOf(List<QuoteItem> selected) {
        if (selected == null || selected.isEmpty()) return List.of();
        return selected.stream()
                .map(QuoteItem::getSymbol)
                .filter(s -> s != null && !s.isEmpty())
                .distinct()
                .toList();
    }

    private void showTabMessage(Tab tab, String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14;");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMaxHeight(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        tab.setContent(new StackPane(label));
    }

    private void renderData(List<QuoteSnapshot> snapshots, FxConverter conv) {
        if (snapshots.isEmpty()) {
            showTabMessage(tabData, "No data found");
            return;
        }
        VBox root = new VBox(20);
        root.setPadding(new Insets(15));
        for (int i = 0; i < snapshots.size(); i++) {
            if (i > 0) root.getChildren().add(new Separator());
            root.getChildren().add(buildSnapshotForm(snapshots.get(i), conv));
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        tabData.setContent(scroll);
    }

    private VBox buildSnapshotForm(QuoteSnapshot s, FxConverter conv) {
        Label header = new Label(s.symbol() + (s.longName().isEmpty() ? "" : " — " + s.longName()));
        header.setStyle("-fx-font-size: 16; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);

        // For snapshot conversion use the snapshot's regularMarketTime date (today for live data).
        LocalDate snapDate = s.regularMarketTime().equals(Instant.EPOCH)
                ? LocalDate.now()
                : s.regularMarketTime().atZone(ZoneId.systemDefault()).toLocalDate();
        ToDoubleFunction<Double> conv1 = v -> convertValue(conv, v, s.currency(), snapDate);

        int row = 0;
        addReadOnlyField(grid, row++, "Symbol",     s.symbol());
        addReadOnlyField(grid, row++, "Name",       s.longName());
        addReadOnlyField(grid, row++, "Price",      formatPrice(conv1.applyAsDouble(s.regularMarketPrice())));
        addReadOnlyField(grid, row++, "Change",     formatPrice(conv1.applyAsDouble(s.change())));
        addReadOnlyField(grid, row++, "Change %",   formatPercent(s.changePercent())); // unit-less
        addReadOnlyField(grid, row++, "Day High",   formatPrice(conv1.applyAsDouble(s.regularMarketDayHigh())));
        addReadOnlyField(grid, row++, "Day Low",    formatPrice(conv1.applyAsDouble(s.regularMarketDayLow())));
        addReadOnlyField(grid, row++, "Prev Close", formatPrice(conv1.applyAsDouble(s.previousClose())));
        addReadOnlyField(grid, row++, "Volume",     formatVolume(s.regularMarketVolume())); // un-converted
        addReadOnlyField(grid, row++, "52w High",   formatPrice(conv1.applyAsDouble(s.fiftyTwoWeekHigh())));
        addReadOnlyField(grid, row++, "52w Low",    formatPrice(conv1.applyAsDouble(s.fiftyTwoWeekLow())));
        addReadOnlyField(grid, row++, "Currency",   currencyDisplay(conv, s.currency()));
        addReadOnlyField(grid, row++, "Exchange",   s.exchangeName());
        addReadOnlyField(grid, row,   "As Of",      formatPublishTime(s.regularMarketTime()));

        return new VBox(8, header, grid);
    }

    private static void addReadOnlyField(GridPane grid, int row, String labelText, String value) {
        Label label = new Label(labelText + ":");
        label.setStyle("-fx-font-weight: bold;");
        TextField field = new TextField(value == null ? "" : value);
        field.setEditable(false);
        field.setFocusTraversable(false);
        field.setPrefWidth(280);
        grid.add(label, 0, row);
        grid.add(field, 1, row);
    }

    /**
     * Currency conversion plan for one render. {@code target} is null in Native mode.
     * {@code rates.get(nativeCcy)} returns the FX series mapping {@code date -> nativeCcy/target}.
     * Edge case note: Yahoo's `GBp` / `ZAc` / `ILA` (sub-unit currencies) are NOT normalized to
     * their main unit here; values from those tickers will under-convert by 100x. Use Native mode
     * for those listings.
     */
    private record FxConverter(String target,
                               Map<String, java.util.NavigableMap<LocalDate, Double>> rates) {
        double convert(double value, String nativeCcy, LocalDate date) {
            if (Double.isNaN(value)) return value;
            if (target == null || nativeCcy == null || nativeCcy.isEmpty()
                    || target.equalsIgnoreCase(nativeCcy)) return value;
            java.util.NavigableMap<LocalDate, Double> series = rates.get(nativeCcy);
            if (series == null || series.isEmpty()) return value;
            Map.Entry<LocalDate, Double> e = series.floorEntry(date);
            if (e == null) e = series.firstEntry();
            return value * e.getValue();
        }
    }

    private FxConverter buildConverter(Set<String> nativeCurrencies, LocalDate from, LocalDate to) {
        if (targetCurrency == TargetCurrency.Native) return null;
        final String target = targetCurrency.name();
        Map<String, java.util.NavigableMap<LocalDate, Double>> rates = new HashMap<>();
        for (String c : nativeCurrencies) {
            if (c == null || c.isEmpty() || c.equalsIgnoreCase(target)) continue;
            try {
                rates.put(c, YahooAPI.getInstance().getFxRates(c, target, from, to));
            } catch (IOException e) {
                log.error("Error loading FX {}->{}", c, target, e);
            }
        }
        return new FxConverter(target, rates);
    }

    private String currencyDisplay(FxConverter conv, String nativeCcy) {
        if (conv == null) return nativeCcy == null ? "" : nativeCcy;
        return conv.target();
    }

    private double convertValue(FxConverter conv, double value, String nativeCcy, LocalDate date) {
        return conv == null ? value : conv.convert(value, nativeCcy, date);
    }

    private static String formatPrice(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%,.2f", v);
    }

    private static String formatPercent(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%+.2f%%", v);
    }

    private static String formatVolume(long v) {
        return v == 0L ? "" : String.format(Locale.ROOT, "%,d", v);
    }

    private void renderHistory(List<HistoricalBar> bars, FxConverter conv) {
        if (bars.isEmpty()) {
            showTabMessage(tabHistory, "No history found for the selected range");
            return;
        }
        TableView<HistoricalBar> table = new TableView<>();
        table.getColumns().add(strCol("Symbol",   HistoricalBar::symbol, 80));
        table.getColumns().add(strCol("Date",     b -> HIST_DATE_FORMAT.format(b.date()), 100));
        table.getColumns().add(numConvCol("Open",      HistoricalBar::open,     conv, 100));
        table.getColumns().add(numConvCol("High",      HistoricalBar::high,     conv, 100));
        table.getColumns().add(numConvCol("Low",       HistoricalBar::low,      conv, 100));
        table.getColumns().add(numConvCol("Close",     HistoricalBar::close,    conv, 100));
        table.getColumns().add(numConvCol("Adj Close", HistoricalBar::adjClose, conv, 100));
        table.getColumns().add(longCol("Volume", HistoricalBar::volume, 120));
        table.getColumns().add(strCol("Currency", b -> currencyDisplay(conv, b.currency()), 80));

        table.getItems().setAll(bars);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        tabHistory.setContent(new StackPane(table));
    }

    private TableColumn<HistoricalBar, String> numConvCol(String title,
                                                          ToDoubleFunction<HistoricalBar> getter,
                                                          FxConverter conv, double width) {
        TableColumn<HistoricalBar, String> col = new TableColumn<>(title);
        col.setCellValueFactory(d -> Bindings.createStringBinding(() -> {
            HistoricalBar b = d.getValue();
            double v = convertValue(conv, getter.applyAsDouble(b), b.currency(), b.date());
            return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%,.2f", v);
        }));
        col.setPrefWidth(width);
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    private static <T> TableColumn<T, String> strCol(String title, java.util.function.Function<T, String> getter, double width) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setCellValueFactory(d -> Bindings.createStringBinding(() -> {
            String v = getter.apply(d.getValue());
            return v == null ? "" : v;
        }));
        col.setPrefWidth(width);
        return col;
    }

    private static <T> TableColumn<T, String> numCol(String title, java.util.function.ToDoubleFunction<T> getter, double width) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setCellValueFactory(d -> Bindings.createStringBinding(() -> {
            double v = getter.applyAsDouble(d.getValue());
            return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%,.2f", v);
        }));
        col.setPrefWidth(width);
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    private static <T> TableColumn<T, String> longCol(String title, java.util.function.ToLongFunction<T> getter, double width) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setCellValueFactory(d -> Bindings.createStringBinding(() -> {
            long v = getter.applyAsLong(d.getValue());
            return v == 0L ? "" : String.format(Locale.ROOT, "%,d", v);
        }));
        col.setPrefWidth(width);
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    private void loadNewsForSelection(List<QuoteItem> selected) {
        // Snapshot the symbols — selectedItems is observable and may mutate before the worker runs.
        final List<String> symbols = symbolsOf(selected);
        if (symbols.isEmpty()) {
            showTabMessage(tabNews, "Select a ticker to see news");
            return;
        }
        showTabMessage(tabNews, "Loading news...");
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

    private void renderNews(List<NewsItem> news) {
        if (news.isEmpty()) {
            showTabMessage(tabNews, "No news found");
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

    private void initYahoooSymbolsCombobox(ConfigManager.AppSettings settings) {
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

        cbxYahooQuote.getEditor().textProperty().addListener((_, _, newValue) -> {
            reloadYahooTickerCombobox(newValue);
            if (newValue != null && !newValue.isEmpty() && !restoringSettings.get())
                persistSettingAsync(ConfigManager.KEY_LAST_SEARCH_TERM, newValue);
        });
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
        // Pre-warm the search with the last term the user used (or a default for first launch).
        // Must run on the FX thread because reloadYahooTickerCombobox touches cbxYahooQuote.
        String prewarm = (settings != null && settings.lastSearchTerm() != null && !settings.lastSearchTerm().isEmpty())
                ? settings.lastSearchTerm() : "ACN";
        final String prewarmTerm = prewarm;
        Platform.runLater(() -> reloadYahooTickerCombobox(prewarmTerm));
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
        // Flush any debounced settings synchronously before tearing down the executors.
        flushPendingSettings();
        debounceExecutor.shutdownNow();
        executorService.shutdownNow();
        // Safety net — auto-save runs on every change, but write once more in case the last change
        // is still in-flight on the (now shut-down) executor.
        ConfigManager.getInstance().saveFavoriteQuotes(yahooTableFavoriteQuotes.getItems());
    }

    private void setDatePickersBasedOnSlider() {
        applyDateRange((int) yahooDateRangeSlider.getValue(), yahooDateRangeLabel, yahooStartDatePicker, yahooEndDatePicker);
    }

    /** Refresh just the slider's text label without touching the date pickers — used at startup. */
    private static void applyDateRangeLabel(int value, Label label) {
        switch (value) {
            case 1:  label.setText("1 day");    break;
            case 2:  label.setText("2 days");   break;
            case 3:  label.setText("3 days");   break;
            case 4:  label.setText("1 week");   break;
            case 5:  label.setText("2 weeks");  break;
            case 6:  label.setText("1 month");  break;
            case 7:  label.setText("2 months"); break;
            case 8:  label.setText("3 months"); break;
            case 9:  label.setText("6 months"); break;
            case 10: label.setText("1 year");   break;
            case 11: label.setText("2 years");  break;
            case 12: label.setText("3 years");  break;
            case 13: label.setText("5 years");  break;
            case 14: label.setText("10 years"); break;
            case 15: label.setText("MAX");      break;
            default: label.setText("");         break;
        }
    }

    /**
     * Coalesce settings writes so a slider drag doesn't generate dozens of disk writes.
     * All distinct keys touched within the debounce window are flushed together in one batch.
     */
    private void persistSettingAsync(String key, Object value) {
        // ConcurrentHashMap doesn't allow null values — encode "remove this key" as a sentinel.
        pendingSettings.put(key, value == null ? NULL_SENTINEL : value);
        ScheduledFuture<?> prev = pendingSettingsFlush.getAndSet(null);
        if (prev != null) prev.cancel(false);
        ScheduledFuture<?> future = debounceExecutor.schedule(this::flushPendingSettings,
                150, TimeUnit.MILLISECONDS);
        pendingSettingsFlush.set(future);
    }

    private void flushPendingSettings() {
        if (pendingSettings.isEmpty()) return;
        Map<String, Object> snapshot = new HashMap<>(pendingSettings);
        pendingSettings.clear();
        snapshot.replaceAll((k, v) -> v == NULL_SENTINEL ? null : v);
        ConfigManager.getInstance().saveSettings(snapshot);
    }

    private static final Object NULL_SENTINEL = new Object();

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
    @FXML protected Tab tabData;
    @FXML protected Tab tabHistory;
    @FXML protected Tab tabChart;
    @FXML protected Tab tabDividend;
    @FXML protected TabPane tabPaneData;
    @FXML protected ComboBox<QuoteItem> cbxYahooQuote;
    @FXML protected ComboBox<TargetCurrency> cbxCurrency;
    @FXML protected Button btnRefresh;
    @FXML protected Button btnRemove;
}
