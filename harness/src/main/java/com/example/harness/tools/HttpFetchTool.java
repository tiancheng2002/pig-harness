package com.example.harness.tools;

import com.example.harness.config.HarnessProperties;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * httpFetch（M5 阶段三）：通用只读网络抓取工具。
 * 安全约束：仅 http/https；默认拒绝环回/私网/链路本地地址（SSRF 防护，可配 allow-private 放开）；
 * 重定向手动跟随 ≤3 跳且每跳重新校验目标地址；不携带 Cookie/本地凭据。
 * 已知限制：DNS 重绑定（解析后连接前域名再变更）不在本工具防护范围。
 */
@Component
public class HttpFetchTool implements Tool {

    private static final int MAX_REDIRECTS = 3;
    private static final int OUTPUT_CAP = 12_000;

    private final HarnessProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpFetchTool(HarnessProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "httpFetch";
    }

    @Override
    public String description() {
        return "抓取公网 HTTP/HTTPS 页面或 API 并返回状态与正文文本（只读；默认禁止访问本机/内网地址）";
    }

    @Override
    public String parametersJson() {
        return """
                {"type":"object","properties":{
                  "url":{"type":"string","description":"完整 URL（http/https）"},
                  "method":{"type":"string","enum":["GET","POST","PUT","DELETE","HEAD"],"description":"默认 GET"},
                  "headers":{"type":"object","description":"附加请求头（键值对）"},
                  "body":{"type":"string","description":"请求体（非 GET 时使用）"},
                  "timeout_seconds":{"type":"integer","description":"超时秒数，默认 20，上限 60"},
                  "max_bytes":{"type":"integer","description":"正文读取上限字节，默认 65536"}
                },"required":["url"]}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String urlStr = str(args.get("url"));
        if (urlStr == null || urlStr.isBlank()) {
            return ToolResult.error("缺少参数 url");
        }
        URI uri;
        try {
            uri = URI.create(urlStr.trim());
        } catch (Exception e) {
            return ToolResult.error("非法 URL: " + urlStr);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return ToolResult.error("仅支持 http/https，收到: " + scheme);
        }
        String method = str(args.get("method"));
        method = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase();
        int timeoutSec = intArg(args.get("timeout_seconds"), 20, 1, 60);
        long maxBytes = intArg(args.get("max_bytes"), 65_536, 1024, 1_048_576L);
        @SuppressWarnings("unchecked")
        Map<String, Object> userHeaders = args.get("headers") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String body = str(args.get("body"));

        StringBuilder out = new StringBuilder();
        try {
            URI current = uri;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                checkAddressAllowed(current);
                HttpRequest.Builder rb = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(timeoutSec))
                        .header("Accept", "*/*");
                for (Map.Entry<String, Object> h : userHeaders.entrySet()) {
                    String k = String.valueOf(h.getKey());
                    if (k.equalsIgnoreCase("host") || k.equalsIgnoreCase("content-length")
                            || k.equalsIgnoreCase("connection")) continue; // 宿主管控头不透传
                    rb.header(k, String.valueOf(h.getValue()));
                }
                HttpRequest request = buildRequest(rb, method, body, hop);
                HttpResponse<InputStream> resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = resp.statusCode();
                String location = resp.headers().firstValue("Location").orElse(null);
                if (status >= 300 && status < 400 && location != null) {
                    if (hop == MAX_REDIRECTS) {
                        return ToolResult.error("重定向超过 " + MAX_REDIRECTS + " 跳，已中止");
                    }
                    URI next = current.resolve(location);
                    if (!next.getScheme().toLowerCase().startsWith("http")) {
                        return ToolResult.error("重定向目标非 http/https: " + next);
                    }
                    current = next;
                    continue;
                }
                String contentType = resp.headers().firstValue("Content-Type").orElse("");
                byte[] data = readCapped(resp.body(), maxBytes);
                boolean truncated = data.length > maxBytes;
                byte[] payload = truncated ? java.util.Arrays.copyOf(data, (int) maxBytes) : data;

                out.append("HTTP ").append(status).append('\n')
                        .append("content-type: ").append(contentType).append('\n');
                long total = truncated ? -1 : payload.length;
                resp.headers().firstValue("Content-Length")
                        .map(s -> out.append("content-length: ").append(s).append('\n'));
                if (truncated) {
                    out.append("（正文超过 max_bytes=").append(maxBytes).append("，已截断）\n");
                }
                out.append('\n');

                if (isBinary(contentType, payload)) {
                    out.append("（二进制内容，已跳过正文")
                            .append(total >= 0 ? "，大小 " + total + " 字节" : "")
                            .append("）");
                } else {
                    String text = new String(payload, charsetOf(contentType));
                    out.append(text.length() > OUTPUT_CAP
                            ? text.substring(0, OUTPUT_CAP) + "…（输出超长已截断）" : text);
                }
                return ToolResult.success(out.toString());
            }
            return ToolResult.error("未获得响应");
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("请求超时（" + timeoutSec + "s）: " + urlStr);
        } catch (SecurityException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("请求失败: " + e.getMessage());
        }
    }

    /** 构造请求：首跳按指定 method（带 body）；重定向后一律 GET。 */
    private HttpRequest buildRequest(HttpRequest.Builder rb, String method, String body, int hop) {
        if (hop > 0 || "GET".equals(method)) {
            return rb.GET().build();
        }
        if ("HEAD".equals(method)) {
            return rb.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        }
        return rb.header("Content-Type", "text/plain; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : body, StandardCharsets.UTF_8))
                .build();
    }

    /** SSRF 校验：解析 host 的所有地址，命中环回/私网/链路本地/ULA 即拒绝（allow-private 放开）。 */
    private void checkAddressAllowed(URI uri) {
        if (props.getHttp().isAllowPrivate()) return;
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("URL 缺少主机名: " + uri);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new SecurityException("域名解析失败: " + host + "（" + e.getMessage() + "）");
        }
        for (InetAddress addr : addresses) {
            if (isPrivate(addr)) {
                throw new SecurityException("出于安全考虑（SSRF 防护），httpFetch 默认禁止访问本机/内网地址: "
                        + host + " → " + addr.getHostAddress()
                        + "。如确需访问，可在 application.yml 配置 harness.http.allow-private=true");
            }
        }
    }

    private boolean isPrivate(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 16 && (b[0] & 0xfe) == 0xfc) return true; // IPv6 ULA fc00::/7
        if (b.length == 4 && (b[0] & 0xff) == 100
                && (b[1] & 0xff) >= 64 && (b[1] & 0xff) <= 127) return true; // CGNAT 100.64/10
        return false;
    }

    private byte[] readCapped(InputStream in, long cap) throws Exception {
        try (in) {
            return in.readNBytes((int) cap + 1);
        }
    }

    private boolean isBinary(String contentType, byte[] data) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        boolean textual = ct.startsWith("text/") || ct.contains("json") || ct.contains("xml")
                || ct.contains("javascript") || ct.contains("html") || ct.isEmpty();
        if (!textual) return true;
        int probe = Math.min(data.length, 512);
        for (int i = 0; i < probe; i++) {
            if (data[i] == 0) return true; // NUL 字节视为二进制
        }
        return false;
    }

    private java.nio.charset.Charset charsetOf(String contentType) {
        if (contentType != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("charset=([\\w\\-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(contentType);
            if (m.find()) {
                try {
                    return java.nio.charset.Charset.forName(m.group(1));
                } catch (Exception ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private int intArg(Object o, int def, int min, long max) {
        try {
            long v = o == null ? def : Long.parseLong(String.valueOf(o));
            return (int) Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
