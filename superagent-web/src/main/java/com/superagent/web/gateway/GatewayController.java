package com.superagent.web.gateway;

import com.superagent.api.ApiPaths;
import com.superagent.core.error.BusyException;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import com.superagent.gateway.ModelRunGrant;
import com.superagent.gateway.ModelRunGrants;
import com.superagent.gateway.UpstreamProxy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTP entry for CLI → control-plane model calls. Auth is the opaque run token only
 * (session cookies are not used); see {@link ModelRunGrants}.
 */
@RestController
@RequestMapping(ApiPaths.GATEWAY)
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);
    /** Matches issue §4: reject oversized bodies before reading further. */
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final Set<String> PROTOCOLS = Set.of(
            ModelRunGrants.PROTOCOL_ANTHROPIC, ModelRunGrants.PROTOCOL_OPENAI);

    private final ModelRunGrants grants;
    private final UpstreamProxy proxy;

    public GatewayController(ModelRunGrants grants, UpstreamProxy proxy) {
        this.grants = grants;
        this.proxy = proxy;
    }

    @RequestMapping("/model/{protocol}/{*rest}")
    public void model(
            @PathVariable String protocol,
            @PathVariable(required = false) String rest,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String proto = protocol == null ? "" : protocol.toLowerCase(Locale.ROOT);
        if (!PROTOCOLS.contains(proto)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "unknown protocol");
            return;
        }
        ModelRunGrant grant;
        try {
            grant = grants.require(tokenHeader(request));
        } catch (ModelRunGrants.UnauthorizedGatewayException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return;
        }
        String expected = ModelRunGrants.protocolFor(grant.cli());
        if (!proto.equals(expected)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "protocol mismatch for cli " + grant.cli());
            return;
        }

        String suffix = suffixAfterProtocol(request.getRequestURI(), proto);
        byte[] body;
        try {
            body = readBody(request);
        } catch (BodyTooLargeException e) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request body too large");
            return;
        }

        Map<String, String> headers = copyHeaders(request);
        response.setStatus(HttpServletResponse.SC_OK);
        // Status may be overwritten once upstream responds; default keeps streaming clients happier if we fail early.
        try {
            int status = proxy.forward(grant, proto, suffix, request.getMethod(), headers, body,
                    response.getOutputStream(), code -> {
                        response.setStatus(code);
                        if (response.getContentType() == null) {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        }
                    });
            log.debug("gateway done run={} status={}", grant.runId(), status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "upstream interrupted");
            }
        } catch (GatewayQuotaInsufficientException e) {
            // #34: QUOTA_INSUFFICIENT — balance cannot cover R; 0 outbound.
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
            }
        } catch (BusyException e) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_CONFLICT, e.getMessage());
            }
        } catch (ForbiddenException e) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
            }
        } catch (RuntimeException | IOException e) {
            log.warn("gateway forward failed run={}: {}", grant.runId(), e.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY,
                        e.getMessage() != null ? e.getMessage() : "upstream failed");
            }
        }
    }

    private static String tokenHeader(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            return auth;
        }
        return request.getHeader("x-api-key");
    }

    static String suffixAfterProtocol(String uri, String protocol) {
        String marker = "/model/" + protocol;
        int i = uri.indexOf(marker);
        if (i < 0) {
            return "";
        }
        String rest = uri.substring(i + marker.length());
        return rest.isEmpty() ? "" : rest;
    }

    private static Map<String, String> copyHeaders(HttpServletRequest request) {
        Map<String, String> map = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            map.put(name, request.getHeader(name));
        }
        return map;
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        int declared = request.getContentLength();
        if (declared > MAX_BODY_BYTES) {
            throw new BodyTooLargeException();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] buf = in.readNBytes(MAX_BODY_BYTES + 1);
            if (buf.length > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
            return buf;
        }
    }

    private static final class BodyTooLargeException extends RuntimeException {
    }
}
