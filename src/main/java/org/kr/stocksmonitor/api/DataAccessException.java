package org.kr.stocksmonitor.api;

import java.io.IOException;

/**
 * Wraps any provider-side failure (HTTP, parse, auth) into a single checked type the controller can handle uniformly.
 * Concrete providers convert their internal errors (typically {@link IOException}) into this.
 */
public class DataAccessException extends Exception {
    public DataAccessException(String message) { super(message); }
    public DataAccessException(String message, Throwable cause) { super(message, cause); }
    public DataAccessException(Throwable cause) { super(cause); }
}
