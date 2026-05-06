package org.kr.stocksmonitor.utils;

import org.apache.commons.collections4.map.LRUMap;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class RestUtils2 {

    private static final Logger log = LogManager.getLogger(RestUtils2.class);

    private static final RestUtils2 instance = new RestUtils2();

    public static RestUtils2 getInstance() {
        return instance;
    }

    private final PoolingHttpClientConnectionManager poolingConnManager = new PoolingHttpClientConnectionManager();
    private final CloseableHttpClient client = HttpClients.custom()
            .setConnectionManager(poolingConnManager)
            .setConnectionManagerShared(true)
            .build();
    private final Map<String, String> cache = Collections.synchronizedMap(new LRUMap<>(30000));

    private RestUtils2() {
    }

    public String runQuery(String url, NameValuePair... parameters) throws IOException {
        Instant start = Instant.now();
        final String key = String.format("%s, %s", url, Arrays.toString(parameters));
        final String cacheHit = cache.get(key);
        if (null != cacheHit) return cacheHit;
        try {
            final ClassicHttpRequest httpGet = ClassicRequestBuilder
                    .get(url)
                    .addParameters(parameters)
                    .build();
            final String result = client.execute(httpGet, classicHttpResponse -> {
                log.debug("Response: {} - {}", classicHttpResponse.getCode(), classicHttpResponse.getReasonPhrase());
                return EntityUtils.toString(classicHttpResponse.getEntity());
            });
            cache.putIfAbsent(key, result);
            return result;
        } finally {
            LogUtils.debugDuration(log, start, "Calling url");
        }
    }
}
