package org.kr.stocksmonitor.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * SPI-backed registry of {@link MarketDataProvider}s. Discovered providers are wrapped in
 * {@link CachingMarketDataProvider} so caching is uniform across implementations.
 *
 * <p>Built once from {@link ServiceLoader#load(Class)} the first time any method is called.
 * Add a new provider by:
 * <ol>
 *   <li>Implementing {@link MarketDataProvider} with a public no-arg constructor.</li>
 *   <li>Listing it in {@code META-INF/services/org.kr.stocksmonitor.api.MarketDataProvider}.</li>
 *   <li>Adding {@code provides org.kr.stocksmonitor.api.MarketDataProvider with <FQN>;}
 *       to your module-info.</li>
 * </ol>
 *
 * <p>The {@link FxRateProvider} look-up uses the same registry — a provider that also implements
 * {@code FxRateProvider} can be retrieved as one. The Yahoo provider does both.</p>
 */
public final class ProviderRegistry {

    private static final Logger log = LogManager.getLogger(ProviderRegistry.class);

    private final Map<String, MarketDataProvider> byId;
    private final Map<String, FxRateProvider> fxById;
    private final String defaultId;

    private static volatile ProviderRegistry instance;

    public static ProviderRegistry getInstance() {
        ProviderRegistry local = instance;
        if (local == null) {
            synchronized (ProviderRegistry.class) {
                local = instance;
                if (local == null) {
                    local = new ProviderRegistry();
                    instance = local;
                }
            }
        }
        return local;
    }

    private ProviderRegistry() {
        Map<String, MarketDataProvider> data = new LinkedHashMap<>();
        Map<String, FxRateProvider> fx = new LinkedHashMap<>();
        for (MarketDataProvider raw : ServiceLoader.load(MarketDataProvider.class)) {
            if (raw == null || raw.id() == null || raw.id().isEmpty()) {
                log.warn("Ignoring provider without id: {}", raw);
                continue;
            }
            CachingMarketDataProvider cached = new CachingMarketDataProvider(raw);
            data.put(raw.id(), cached);
            // If the underlying provider also offers FX, expose that capability separately.
            if (raw instanceof FxRateProvider fxRaw) {
                fx.put(raw.id(), fxRaw);
            }
            log.info("Registered market-data provider: {} ({})", raw.id(), raw.displayName());
        }
        this.byId = Collections.unmodifiableMap(data);
        this.fxById = Collections.unmodifiableMap(fx);
        this.defaultId = data.isEmpty() ? null : data.keySet().iterator().next();
    }

    /** All discovered providers, in discovery order. */
    public List<MarketDataProvider> all() {
        return List.copyOf(byId.values());
    }

    public Optional<MarketDataProvider> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * @throws IllegalStateException if no provider is registered with that id and no default exists.
     *         Callers handling unknown ids gracefully should use {@link #find(String)} instead.
     */
    public MarketDataProvider get(String id) {
        MarketDataProvider p = byId.get(id);
        if (p != null) return p;
        if (defaultId != null) {
            log.warn("Unknown provider id '{}', falling back to default '{}'", id, defaultId);
            return byId.get(defaultId);
        }
        throw new IllegalStateException("No market-data providers registered");
    }

    public Optional<MarketDataProvider> getDefault() {
        return defaultId == null ? Optional.empty() : Optional.of(byId.get(defaultId));
    }

    public String defaultId() { return defaultId; }

    public Optional<FxRateProvider> findFx(String id) {
        return Optional.ofNullable(fxById.get(id));
    }

    /** First registered FX-capable provider, or {@link Optional#empty()} if none. */
    public Optional<FxRateProvider> getDefaultFx() {
        return fxById.isEmpty()
                ? Optional.empty()
                : Optional.of(fxById.values().iterator().next());
    }
}
