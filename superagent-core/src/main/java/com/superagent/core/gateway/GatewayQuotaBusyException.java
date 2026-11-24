package com.superagent.core.gateway;

import com.superagent.core.error.BusyException;

/**
 * Remaining quota is temporarily held by in-flight reserves; retry after a short wait (#34 QUOTA_BUSY).
 */
public class GatewayQuotaBusyException extends BusyException {

    public GatewayQuotaBusyException(String message) {
        super(message);
    }
}
