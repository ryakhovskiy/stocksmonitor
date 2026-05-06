package org.kr.stocksmonitor;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kr.stocksmonitor.config.ConfigManager;
import org.kr.stocksmonitor.exceptions.RestCallException;
import org.kr.stocksmonitor.polygon.NewsArticle;
import org.kr.stocksmonitor.polygon.PolygonAPI;
import org.kr.stocksmonitor.polygon.Ticker;
import org.kr.stocksmonitor.utils.FileUtils;
import org.kr.stocksmonitor.utils.LogUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class PolygonController implements PolygonAPI.ProgressCallback {

    private final Logger log = LogManager.getLogger(PolygonController.class);
    private final StocksMonitorController controller;
    private final PolygonAPI api = new PolygonAPI();

    private Set<Ticker> tickers = new HashSet<>();
    private final Set<String> assetClasses = new HashSet<>();
    private final Map<String, Set<String>> tickerTypesPerAssetClass = new HashMap<>();
    private String lastSelectedAsset = "";

    public PolygonController(StocksMonitorController controller) {
        this.controller = controller;
        if (null != controller)
            initialize();
    }

    public void initialize() {
        initTickersCombobox();
        initSettings();
        initDatePickers();
        loadTickers();
        initTableView();
    }

    private void createNewsTableView(List<NewsArticle> news) {
        TableView<NewsArticleFX> tableView = new TableView<>();

        TableColumn<NewsArticleFX, NewsArticleFX.ImageLoadingTask> imageColumn = new TableColumn<>("Image");
        imageColumn.setCellFactory(_ -> new TableCellWithImage());
        imageColumn.setPrefWidth(128);
        imageColumn.setCellValueFactory((new PropertyValueFactory<>("image")));

        TableColumn<NewsArticleFX, Hyperlink> titleColumn = new TableColumn<>("Title");
        titleColumn.setCellFactory(_ -> new TableCell<>() {
            private final Hyperlink hyperlink = new Hyperlink();

            {
                hyperlink.setOnAction(_ -> {
                    NewsArticleFX article = getTableView().getItems().get(getIndex());
                    if (article != null) {
                        if (null != controller.hostServices)
                            controller.hostServices.showDocument(article.getArticleUrl());
                    }
                });
            }

            @Override
            protected void updateItem(Hyperlink item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    NewsArticleFX article = getTableView().getItems().get(getIndex());
                    hyperlink.setText(article.getTitle().getText());
                    setGraphic(hyperlink);
                    setWrapText(true);
                }
            }
        });
        titleColumn.setCellValueFactory((new PropertyValueFactory<>("title")));
        titleColumn.setPrefWidth(300);


        // Create TableColumn for description
        TableColumn<NewsArticleFX, String> descriptionColumn = new TableColumn<>("Description");
        descriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));
        descriptionColumn.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty) {
                    setText(null);
                } else {
                    setText(item);
                    setWrapText(true);
                }
            }
        });
        descriptionColumn.setPrefWidth(450);

        // Add columns to TableView
        tableView.getColumns().addAll(imageColumn, titleColumn, descriptionColumn);

        var newsFX = news.stream().map(NewsArticleFX::new).toList();

        tableView.getItems().addAll(newsFX);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        StackPane pane = new StackPane(tableView);
        controller.tabNews.setContent(pane);
    }

    private void initTickersCombobox() {
        ComboBoxListViewSkin skin = new ComboBoxListViewSkin(controller.cbxTicker);
        skin.getPopupContent().addEventFilter(KeyEvent.ANY, e -> {
            if (e.getCode() == KeyCode.SPACE) {
                e.consume();
            }
        });
        controller.cbxTicker.setSkin(skin);

        controller.cbxTicker.valueProperty().addListener((_, _, newValue) -> {
            if (!(newValue instanceof Ticker))
                return;
            log.debug(newValue);
            var items = controller.tblTickers.getItems();
            if (!items.contains(newValue))
                items.add((Ticker) newValue);
        });
    }

    private void initSettings() {
        controller.tfApiKey.setText(api.getApiKey());
        controller.tfApiKey.textProperty().addListener((observable, oldValue, newValue) -> {
            api.updateApiKey(newValue);
        });
    }

    private void loadTickers() {
        try {
            tickers = FileUtils.readFromFile();
            loadAssetClassesCombobox();
        } catch (Exception e) {
            log.error(e);
            showErrorAlert();
        }
    }

    private void downloadTickers() {
        Task<Set<Ticker>> task = new Task<>() {
            @Override
            protected Set<Ticker> call() {
                try {
                    tickers = api.loadAllTickers(PolygonController.this);
                } catch (Exception e) {
                    controller.showAlert(e, "Error while downloading tickers");
                }
                return tickers;
            }
        };

        task.setOnRunning(_ -> {
            controller.progressIndicator.setVisible(true);
            controller.progressLabel.setVisible(true);
            controller.tabSettings.getTabPane().getSelectionModel().select(controller.tabSettings);
        });
        task.setOnSucceeded(_ -> {
            controller.progressIndicator.setVisible(false);
            controller.progressLabel.setVisible(false);
            tickers = task.getValue();
        });

        controller.executorService.submit(task);
    }

    private void loadAssetClassesCombobox() {
        if (tickers.isEmpty()) return;
        if (assetClasses.isEmpty()) {
            Set<String> filteredValues = tickers.stream().map(Ticker::getMarket).collect(Collectors.toSet());
            assetClasses.addAll(filteredValues);
        }
        controller.cbxAssetClass.getItems().clear();
        controller.cbxAssetClass.getItems().addAll(assetClasses);
    }

    private void loadTickerNews(List<Ticker> tickers) {
        if (!controller.tabNews.isSelected())
            return;

        LocalDate start = controller.startDatePicker.getValue();
        LocalDate end = controller.endDatePicker.getValue();
        List<NewsArticle> news = new ArrayList<>();
        for (Ticker t : tickers) {
            try {
                news.addAll(api.getTickerNews(t, start, end));
            } catch (RestCallException e) {
                log.error(e);
            }
        }
        refreshNewsTab(news);
    }

    private void refreshNewsTab(List<NewsArticle> news) {
        Instant startCall = Instant.now();

        controller.tabNews.setContent(null);
        if (null == news || news.isEmpty()) {
            Label label = new Label("No news found");
            label.setStyle("-fx-font-weight: bold; -fx-font-size: 16;");
            label.setMaxWidth(Double.MAX_VALUE);
            label.setMaxHeight(Double.MAX_VALUE);
            label.setAlignment(Pos.CENTER);
            StackPane pane = new StackPane(label);
            controller.tabNews.setContent(pane);
        } else {
            createNewsTableView(news);
        }
        LogUtils.debugDuration(log, startCall, "refreshNewsTab(List<NewsArticle> news)");
    }

    public void btnRemoveSelectedFavoriteTickersHit(ActionEvent actionEvent) {
        ObservableList<Ticker> selectedItems = controller.tblTickers.getSelectionModel().getSelectedItems();
        controller.tblTickers.getItems().removeAll(selectedItems);
    }

    protected void removeAllTickers() {
        controller.tblTickers.getItems().clear();
    }

    protected void handleCbxAssetClassChanged() {
        Instant start = Instant.now();
        String assetClass = controller.cbxAssetClass.getSelectionModel().getSelectedItem();
        if (assetClass.equals(lastSelectedAsset)) return;
        lastSelectedAsset = assetClass;
        controller.cbxTickerType.getItems().clear();

        if (tickerTypesPerAssetClass.isEmpty())
            loadMarketTickerTypes(tickers);

        controller.cbxTickerType.getItems().addAll(tickerTypesPerAssetClass.getOrDefault(assetClass, Collections.emptySet()));
        reloadTickerCombobox();
        LogUtils.debugDuration(log, start, "handleCbxAssetClassChanged");
    }

    private void loadMarketTickerTypes(Set<Ticker> tickers) {
        Instant start = Instant.now();
        synchronized (tickerTypesPerAssetClass) {
            if (!tickerTypesPerAssetClass.isEmpty()) return;
            for (Ticker t : tickers) {
                var set = tickerTypesPerAssetClass.get(t.getMarket());
                if (null == set) {
                    set = new HashSet<>();
                    set.add(t.getType());
                    tickerTypesPerAssetClass.put(t.getMarket(), set);
                } else {
                    set.add(t.getType());
                }
            }
        }
        LogUtils.debugDuration(log, start, "forming the map tickerTypesPerAssetClass");
    }

    protected void cbxTickerTypeChanged(ActionEvent actionEvent) {
        reloadTickerCombobox();
    }

    private void reloadTickerCombobox() {
        Instant start = Instant.now();
        String assetClass = controller.cbxAssetClass.getSelectionModel().getSelectedItem().toString();

        Object objTickerType = controller.cbxTickerType.getSelectionModel().getSelectedItem();
        String tickerType = null == objTickerType ? "" : objTickerType.toString();

        List<Ticker> data;
        if (tickerType.isEmpty()) {
            //load all tickers for this asset class
            data = tickers.stream().filter(t -> t.getMarket().equals(assetClass)).toList();
        } else {
            //load tickers for this asset class and this particular ticker type
            data = tickers.stream().filter(t -> t.getMarket().equals(assetClass) && t.getType().equals(tickerType)).toList();
        }

        if (data.isEmpty()) return;

        ObservableList<Ticker> observableList = FXCollections.observableArrayList(data);
        FilteredList<Ticker> filteredData = new FilteredList<>(observableList);
        controller.cbxTicker.setItems(filteredData);
        updateTickerCombobox(filteredData);
        LogUtils.debugDuration(log, start, "reloadTickerCombobox");
    }

    private void updateTickerCombobox(FilteredList<Ticker> filteredData) {
        Instant start = Instant.now();
        UnaryOperator<TextFormatter.Change> filter = change -> {
            if (change.getSelection().getStart() == 0 && change.getSelection().getEnd() == 0) return change;
            String newText = change.getControlNewText();
            if (newText.isEmpty()) {
                controller.cbxTicker.hide();
            } else {
                filteredData.setPredicate(ticker ->
                        ticker.getTicker().toLowerCase().contains(newText.toLowerCase()) ||
                                ticker.getName().toLowerCase().contains(newText.toLowerCase()));
                controller.cbxTicker.show();
            }
            return change;
        };
        TextFormatter<String> textFormatter = new TextFormatter<>(filter);
        controller.cbxTicker.setEditable(true);
        controller.cbxTicker.getEditor().setTextFormatter(textFormatter);

        // Set up string converter for correct display
        controller.cbxTicker.setConverter(new StringConverter<Ticker>() {
            @Override
            public String toString(Ticker object) {
                if (object == null) return null;
                return object.getTicker() + ": " + object.getName();
            }

            @Override
            public Ticker fromString(String string) {
                return null; // Not used for auto-completion
            }
        });

        // Bind the filtered list to the ComboBox's items
        controller.cbxTicker.setItems(filteredData);
        LogUtils.debugDuration(log, start, "updating the cbxTicker");
    }

    private void handleSelectedTickers(List<Ticker> tickers) {
        log.debug(tickers);
        loadTickerNews(tickers);
    }

    private void initTableView() {
        controller.tblTickers.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        controller.tblTickers.getSelectionModel().getSelectedItems().addListener((ListChangeListener<Ticker>) change -> {
            List<Ticker> selectedTickers = (List<Ticker>)change.getList();
            handleSelectedTickers(selectedTickers);
        });

        loadFavoriteTickers();
        controller.tabPaneData.getSelectionModel().selectedItemProperty().addListener((_, oldValue, newValue) -> {
            if (newValue.equals(controller.tabNews)) handleTabNewsSelection();
        });
    }

    private void handleTabNewsSelection() {
        log.debug("News tab is selected");
        List<Ticker> tickers = controller.tblTickers.getSelectionModel().getSelectedItems();
        handleSelectedTickers(tickers);
    }

    private void updateApiKey() {
        String apiKey = controller.tfApiKey.getText();
        if (apiKey.equals(this.api.getApiKey())) return;
        ConfigManager.getInstance().saveApiKey(apiKey);
    }

    private void initDatePickers() {
        controller.dateRangeSlider.setValue(1);
        setDatePickersBasedOnSlider();
        controller.dateRangeSlider.valueProperty().addListener((_, _, _) -> setDatePickersBasedOnSlider());
    }

    protected void shutdown() throws IOException {
        if (!tickers.isEmpty()) FileUtils.saveToFile(tickers);
        updateApiKey();
        saveFavoriteTickers();
    }

    private void saveFavoriteTickers() {
        List<Ticker> favoriteTickers = controller.tblTickers.getItems();
        ConfigManager.getInstance().saveFavoriteTickers(favoriteTickers);
    }

    private void loadFavoriteTickers() {
        log.debug("loading favorite tickers...");
        List<String> favoriteTickerSymbols = ConfigManager.getInstance().readFavoriteTickerSymbols();
        var favoriteTickers = tickers.stream().filter(t -> favoriteTickerSymbols.contains(t.getTicker())).toList();
        log.debug("adding favorite tickers to the table: {}; {}", favoriteTickerSymbols, favoriteTickers);
        controller.tblTickers.getItems().addAll(favoriteTickers);
    }

    private void startDownload() {
        try {
            downloadTickers();
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Failed to load tickers from the external source");
            alert.setContentText(e.getMessage());
            alert.show();
        }
    }

    protected void showErrorAlert() {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("Failed to load tickers from file");
        alert.setContentText("Do you want to download tickers from an external source?");
        ButtonType yesButton = new ButtonType("Yes");
        ButtonType noButton = new ButtonType("No");
        alert.getButtonTypes().setAll(yesButton, noButton);
        Optional<ButtonType> result = alert.showAndWait();

        if (result.isPresent() && result.get() == yesButton) {
            startDownload();
        }
    }

    @Override
    public void onProgressUpdate(String progress) {
        log.debug(progress);
        Platform.runLater(() -> {
            controller.progressLabel.setText(progress);
        });
    }

    private void setDatePickersBasedOnSlider() {
        StocksMonitorController.applyDateRange(
                (int) controller.dateRangeSlider.getValue(),
                controller.dateRangeLabel,
                controller.startDatePicker,
                controller.endDatePicker);
    }
}
