package org.kr.stocksmonitor.utils;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kr.stocksmonitor.config.ConfigManager;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

import org.kr.stocksmonitor.exceptions.RestCallException;

public class RestUtils {

    private static final Logger logger = LogManager.getLogger(RestUtils.class);
    private static final LinkedList<Long> lastXCalls = new LinkedList<>();

    private static RestUtils instance = new RestUtils();
    public static RestUtils getInstance() {
        return instance;
    }

    private ConfigManager config = ConfigManager.getInstance();
    private final int maxCallsPerMinute = config.readPolygonMaxCallsPerMinute();

    private RestUtils() {

    }

    public String callRestEndpoint(String host, String endpoint, String apiKey, List<NameValuePair> parameters,
                                   RestEndpointType endpointType) throws RestCallException {
        Instant start = Instant.now();
        if (endpointType == RestEndpointType.POLYGON)
            enforceRateLimit();
        if (endpointType == RestEndpointType.YAHOO)
            manageYahooCrumbsAndCookies(parameters);
        String result = "";
        HttpGet request = new HttpGet(host + endpoint);
        URIBuilder builder = null;
        try {
            builder = new URIBuilder(request.getUri()).addParameters(parameters);
        } catch (URISyntaxException e) {
            throw new RestCallException(e);
        }
        String url = "";
        if (logger.isDebugEnabled())
            url = builder.toString();
        logger.debug("calling endpoint: {}", url);
        if (endpointType == RestEndpointType.POLYGON)
            builder.addParameter("apiKey", apiKey);
        if (endpointType == RestEndpointType.YAHOO)
            builder.addParameter("crumb", apiKey);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            request.setUri(builder.build());
            try (CloseableHttpResponse httpResponse = client.execute(request)) {
                HttpEntity entity = httpResponse.getEntity();
                if (entity != null) {
                    result = EntityUtils.toString(entity);
                }
            }
        } catch (IOException | URISyntaxException | ParseException e) {
            throw new RestCallException(e);
        }

        LogUtils.debugDuration(logger, start, String.format("calling endpoint '%s'", url));
        return result;
    }

    public String callPolygonEndpoint(String host, String endpoint, String apiKey, List<NameValuePair> parameters) throws RestCallException {
        return callRestEndpoint(host, endpoint, apiKey, parameters, RestEndpointType.POLYGON);
    }

    private void manageYahooCrumbsAndCookies(List<NameValuePair> parameters) {

    }

    private void enforceRateLimit() {
        logger.debug("enforcing rate limit...");
        Instant start = Instant.now();
        long sleepMs = 0;
        synchronized (lastXCalls) {
            lastXCalls.offer(start.toEpochMilli());
            while (lastXCalls.size() >= maxCallsPerMinute) {
                long firstCall = lastXCalls.pollFirst();
                final long timeSinceFirstCall = System.currentTimeMillis() - firstCall;
                if (timeSinceFirstCall <= 60_000) {
                    sleepMs = 60_050 - timeSinceFirstCall;
                    break;
                }
            }
        }
        if (sleepMs > 0) {
            try {
                logger.debug("enforcing rate limit, sleeping for: {}", sleepMs);
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                logger.error(e);
                Thread.currentThread().interrupt();
            }
        }
        LogUtils.debugDuration(logger, start, "enforceRateLimit()");
    }

}

