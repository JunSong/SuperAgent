package com.superagent.core.gateway;

import com.superagent.core.error.ForbiddenException;

/** Base quota B is used up; new turns are refused (QUOTA_EXHAUSTED). */
public class GatewayQuotaExhaustedException extends ForbiddenException {

    public GatewayQuotaExhaustedException(String message) {
        super(message);
    }
}
