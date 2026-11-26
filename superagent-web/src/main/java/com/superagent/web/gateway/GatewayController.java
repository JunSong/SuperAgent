package com.superagent.web.gateway;

import com.superagent.api.ApiPaths;
import com.superagent.core.error.BusyException;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.GatewayQuotaBusyException;
import com.superagent.core.gateway.GatewayQuotaExhaustedException;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import com.superagent.gateway.GatewayRoutePolicy;
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
import java.nio.charset.StandardCharsets;
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
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "unknown protocol");
            return;
        }
        ModelRunGrant grant;
        try {
            grant = grants.require(tokenHeader(request));
        } catch (ModelRunGrants.UnauthorizedGatewayException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return;
        }
        String expected = ModelRunGrants.protocolFor(grant.cli());
        if (!proto.equals(expected)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "protocol mismatch for cli " + grant.cli());
            return;
        }

        String suffix = suffixAfterProtocol(request.getRequestURI(), proto);
        byte[] body;
        try {
            body = readBody(request);
        } catch (BodyTooLargeException e) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request body too large");
            return;
        }
        try {
            GatewayRoutePolicy.assertAllowed(proto, request.getMethod(), suffix, grant.model(), body);
        } catch (ForbiddenException e) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
            return;
        } catch (ModelRunGrants.UnauthorizedGatewayException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return;
        }

        Map<String, String> headers = copyHeaders(request);
        response.setStatus(HttpServletResponse.SC_OK);
        // Status may be overwritten once upstream responds; default keeps streaming clients happier if we fail early.
        try {
            int status = proxy.forward(grant, proto, suffix, request.getMethod(), headers, body,
                    response.getOutputStream(), (code, contentType) -> {
                        response.setStatus(code);
                        if (contentType != null && !contentType.isBlank()) {
                            response.setContentType(contentType);
                        } else if (response.getContentType() == null) {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        }
                        try {
                            response.flushBuffer();
                        } catch (IOException ignored) {
                            // best-effort: commit headers so SSE can start flowing
                        }
                    });
            log.debug("gateway done run={} status={}", grant.runId(), status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "upstream interrupted");
            }
        } catch (GatewayQuotaBusyException e) {
            // #34: QUOTA_BUSY — retryable; keep 409 distinct from exhausted/insufficient.
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
            }
        } catch (GatewayQuotaInsufficientException | GatewayQuotaExhaustedException e) {
            // #34: QUOTA_* — no outbound.
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
            }
        } catch (BusyException e) {
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
            }
        } catch (ForbiddenException e) {
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
            }
        } catch (ModelRunGrants.UnauthorizedGatewayException e) {
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            }
        } catch (RuntimeException | IOException e) {
            log.warn("gateway forward failed run={}: {}", grant.runId(), e.getMessage());
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_BAD_GATEWAY,
                        e.getMessage() != null ? e.getMessage() : "upstream failed");
            }
        }
    }

    /**
     * Prefer {@code setStatus} over {@code sendError}: the latter triggers an ERROR dispatch to {@code /error},
     * which Spring Security then redirects to {@code /login} (CLIs see 302 HTML, e.g. "Messages transport failed").
     * Body is Anthropic-shaped JSON so harnesses surface {@code error.message} instead of a bare status.
     */
    static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String msg = message == null ? "" : message;
        String type = status == HttpServletResponse.SC_UNAUTHORIZED ? "authentication_error"
                : status == HttpServletResponse.SC_FORBIDDEN ? "permission_error"
                : status == 409 ? "overloaded_error"
                : "api_error";
        String json = "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":"
                + jsonString(msg) + "}}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String tokenHeader(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            return auth;
        }
        return request.getHeader("x-api-key");
    }

    static String suffixAfterProtocol(String uri, String protocol) {
        if (uri == null || protocol == null) {
            return "";
        }
        // Match case-insensitively: clients may send /Model/ANTHROPIC/... while proto is lowercased.
        String lowerUri = uri.toLowerCase(Locale.ROOT);
        String marker = "/model/" + protocol.toLowerCase(Locale.ROOT);
        int i = lowerUri.indexOf(marker);
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
