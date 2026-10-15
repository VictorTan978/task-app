package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A browser for the Google sign-in tests: a cookie jar, Angular's X-XSRF-TOKEN header on JSON writes, the hidden
 * form POST that starts the flow (with {@code _csrf}), and following the provider's redirect back to the app's
 * callback. Never follows redirects itself. Absolute URLs on the app's base URL ({@link #APP_BASE_URL}) are sent to
 * the test server.
 */
final class GoogleBrowser {

    /** {@code app.email.base-url} in the test properties. */
    static final String APP_BASE_URL = "https://app.taskapp.test";
    static final String START = "/api/auth/oauth2/authorization/google";
    static final String PASSWORD = "correct-horse";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final int port;
    /** Sent on every request, e.g. X-Forwarded-For (trusted from loopback) to act as another client IP. */
    final Map<String, String> headers = new LinkedHashMap<>();
    final Map<String, String> cookies = new LinkedHashMap<>();

    GoogleBrowser(int port) {
        this.port = port;
    }

    record Resp(int status, String body, List<String> setCookies, String location) {

        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }

        String setCookie(String name) {
            return setCookies.stream().filter(c -> c.startsWith(name + "=")).reduce((a, b) -> b).orElse(null);
        }
    }

    /** Gets the XSRF-TOKEN cookie like the app's first request. */
    GoogleBrowser start() throws Exception {
        get("/api/auth/providers");
        assertThat(cookies).containsKey("XSRF-TOKEN");
        return this;
    }

    Resp get(String pathOrUrl) throws Exception {
        return send(builder(pathOrUrl).GET());
    }

    Resp post(String path, String json) throws Exception {
        return write("POST", path, json);
    }

    Resp put(String path, String json) throws Exception {
        return write("PUT", path, json);
    }

    Resp delete(String path) throws Exception {
        return write("DELETE", path, null);
    }

    private Resp write(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = builder(path).method(method,
                json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        if (cookies.containsKey("XSRF-TOKEN")) {
            b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        return send(b);
    }

    /** The frontend's hidden form: {@code intent} and {@code _csrf} read from the cookie at submit time. */
    Resp startGoogle(String intent) throws Exception {
        return startGoogle(intent, cookies.get("XSRF-TOKEN"));
    }

    Resp startGoogle(String intent, String csrf) throws Exception {
        StringBuilder form = new StringBuilder();
        if (intent != null) {
            form.append("intent=").append(FakeGoogle.encode(intent));
        }
        if (csrf != null) {
            form.append(form.isEmpty() ? "" : "&").append("_csrf=").append(FakeGoogle.encode(csrf));
        }
        return send(builder(START).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString())));
    }

    /** Start, then the user approves at the provider: the callback URL the provider sends the browser to. */
    String approvedCallback(FakeGoogle google, String intent, String subject, String claims) throws Exception {
        Resp start = startGoogle(intent);
        assertThat(start.status()).as(start.body()).isEqualTo(302);
        assertThat(start.location()).as("start redirect").startsWith(google.issuer());
        return google.approve(start.location(), subject, claims);
    }

    /** The whole round trip; returns the callback's response (302 to the frontend). */
    Resp google(FakeGoogle google, String intent, String subject, String claims) throws Exception {
        return get(approvedCallback(google, intent, subject, claims));
    }

    Resp register(String name, String email) throws Exception {
        Resp r = post("/api/auth/register", "{\"displayName\":\"" + name + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r;
    }

    Resp login(String email, String password, boolean rememberMe) throws Exception {
        return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"rememberMe\":" + rememberMe + "}");
    }

    Resp me() throws Exception {
        return get("/api/auth/me");
    }

    private HttpRequest.Builder builder(String pathOrUrl) {
        String path = pathOrUrl.startsWith(APP_BASE_URL) ? pathOrUrl.substring(APP_BASE_URL.length()) : pathOrUrl;
        assertThat(path).as("request on the app").startsWith("/");
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
        return new Resp(r.statusCode(), r.body(), setCookies, r.headers().firstValue("Location").orElse(null));
    }
}
