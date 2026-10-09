package com.taskapp.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Real-HTTP test client for the proxy/HTTPS tests: a cookie jar that ignores Secure (like a browser on the
 * HTTPS side of a TLS proxy would send them), echoes XSRF-TOKEN as X-XSRF-TOKEN like Angular, never follows
 * redirects, and adds fixed headers (e.g. X-Forwarded-Proto / X-Forwarded-For) to every request.
 */
class ProxyHttpClient {

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final int port;
    final Map<String, String> headers = new LinkedHashMap<>();
    final Map<String, String> cookies = new LinkedHashMap<>();

    ProxyHttpClient(int port) {
        this.port = port;
    }

    ProxyHttpClient header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    Resp get(String path) throws Exception {
        return send(builder(path).GET());
    }

    Resp post(String path, String jsonBody) throws Exception {
        HttpRequest.Builder b = builder(path).header("Content-Type", "application/json")
                .POST(jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (cookies.containsKey("XSRF-TOKEN")) {
            b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        return send(b);
    }

    private HttpRequest.Builder builder(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(b::header);
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
        }
        return b;
    }

    private Resp send(HttpRequest.Builder b) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        List<String> setCookies = r.headers().allValues("Set-Cookie");
        for (String sc : setCookies) {
            String[] nameValue = sc.split(";", 2)[0].split("=", 2);
            if (sc.contains("Max-Age=0")) {
                cookies.remove(nameValue[0]);
            } else {
                cookies.put(nameValue[0], nameValue[1]);
            }
        }
        return new Resp(r.statusCode(), r.body(), setCookies,
                r.headers().firstValue("Location").orElse(null),
                r.headers().firstValue("Strict-Transport-Security").orElse(null));
    }

    record Resp(int status, String body, List<String> setCookies, String location, String hsts) {

        /** The last Set-Cookie for {@code name}, or null. */
        String setCookie(String name) {
            return setCookies.stream().filter(c -> c.startsWith(name + "=")).reduce((a, b) -> b).orElse(null);
        }
    }

    static boolean isSecure(String setCookie) {
        return setCookie.matches("(?i).*;\\s*Secure\\s*(;.*)?");
    }

    static String registerBody(String name, String email) {
        return "{\"displayName\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"correct-horse\"}";
    }

    static String loginBody(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"wrong-horse\"}";
    }
}
