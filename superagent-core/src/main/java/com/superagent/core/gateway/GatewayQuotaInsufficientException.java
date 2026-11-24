package com.superagent.core.gateway;

import com.superagent.core.error.ForbiddenException;

/** Available balance cannot cover the full reservation R (QUOTA_INSUFFICIENT). */
public class GatewayQuotaInsufficientException extends ForbiddenException {

    public GatewayQuotaInsufficientException(String message) {
        super(message);
    }
}
