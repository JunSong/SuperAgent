package com.superagent.core.gateway;

import com.superagent.core.error.ConflictException;

/** Same operation_id with different parameters (HTTP 409). */
public class GatewayConflictException extends ConflictException {

    public GatewayConflictException(String message) {
        super(message);
    }
}
