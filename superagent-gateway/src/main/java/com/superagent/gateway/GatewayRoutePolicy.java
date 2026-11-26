package com.superagent.gateway;

import com.superagent.core.error.ForbiddenException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Restricts a run token to the protocol methods/paths it was issued for, and to the grant model (R08).
 */
public final class GatewayRoutePolicy {

    private static final Set<String> ANTHROPIC_PATHS = Set.of("/v1/messages");
    /**
     * OpenAI-compatible bases often already end in {@code /v1} ({@code api.openai.com/v1}).
     * Accept both {@code /v1/...} (CLI appended full path) and {@code /chat/completions} style suffixes.
     */
    private static final Set<String> OPENAI_PATHS = Set.of(
            "/v1/chat/completions", "/v1/responses",
            "/chat/completions", "/responses");

    private GatewayRoutePolicy() {
    }

    public static String normalizeSuffix(String suffix) {
        if (suffix == null || suffix.isBlank() || "/".equals(suffix)) {
            return "";
        }
        String raw = suffix.startsWith("/") ? suffix : "/" + suffix;
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ForbiddenException("非法网关路径");
        }
        if (decoded.contains("..") || decoded.indexOf('\\') >= 0) {
            throw new ForbiddenException("非法网关路径");
        }
        // collapse duplicate slashes
        StringBuilder sb = new StringBuilder(decoded.length());
        char prev = 0;
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == '/' && prev == '/') {
                continue;
            }
            sb.append(c);
            prev = c;
        }
        String n = sb.toString();
        if (n.length() > 1 && n.endsWith("/")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    public static void assertAllowed(String protocol, String method, String suffix, String grantModel, byte[] body) {
        String path = normalizeSuffix(suffix);
        String m = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
        if (!"POST".equals(m)) {
            throw new ForbiddenException("受管网关仅允许 POST");
        }
        if (ModelRunGrants.PROTOCOL_ANTHROPIC.equals(protocol)) {
            if (!ANTHROPIC_PATHS.contains(path)) {
                throw new ForbiddenException("未授权的 Anthropic 路径: " + path);
            }
        } else if (ModelRunGrants.PROTOCOL_OPENAI.equals(protocol)) {
            if (!OPENAI_PATHS.contains(path)) {
                throw new ForbiddenException("未授权的 OpenAI 路径: " + path);
            }
        } else {
            throw new ForbiddenException("unknown protocol");
        }
        if (grantModel != null && !grantModel.isBlank()) {
            String reqModel = ReservationEstimator.requestModel(body);
            if (reqModel != null && !reqModel.isBlank() && !grantModel.equals(reqModel)) {
                throw new ForbiddenException("请求模型与运行令牌不一致");
            }
        }
    }
}
