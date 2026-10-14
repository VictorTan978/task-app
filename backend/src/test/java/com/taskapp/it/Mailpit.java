package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Mailpit (same image as the docker-compose "mail" service) for integration tests: the app sends to its SMTP port,
 * tests read the messages through its HTTP API (https://mailpit.axllent.org/docs/api-v1/).
 */
final class Mailpit {

    static final String IMAGE = "axllent/mailpit:v1.31.3";
    private static final int SMTP = 1025;
    private static final int HTTP = 8025;

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    Mailpit(GenericContainer<?> container) {
        this.container = container;
    }

    @SuppressWarnings("resource")
    static GenericContainer<?> container() {
        return new GenericContainer<>(IMAGE)
                .withExposedPorts(SMTP, HTTP)
                .waitingFor(Wait.forHttp("/readyz").forPort(HTTP));
    }

    /** Points the app's {@code spring.mail.*} at this container (plain SMTP, no login, like the dev profile). */
    static void register(GenericContainer<?> container, DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", container::getHost);
        registry.add("spring.mail.port", () -> container.getMappedPort(SMTP));
    }

    /** One received email. {@code from} is "Name <address>". */
    record Message(String id, String from, List<String> to, String subject, String text, String html) {

        /** The token of the first {@code <path>?token=...} link in the text part. */
        String token(String path) {
            Matcher m = Pattern.compile(Pattern.quote(path) + "\\?token=([A-Za-z0-9_-]+)").matcher(text);
            assertThat(m.find()).as("link %s in:%n%s", path, text).isTrue();
            return m.group(1);
        }
    }

    /** Every message sent to {@code address} (case-insensitive), oldest first, with bodies. */
    List<Message> to(String address) {
        List<Message> result = new ArrayList<>();
        // Mailpit lists the newest first.
        for (JsonNode summary : get("/api/v1/messages?limit=1000").get("messages")) {
            boolean match = false;
            for (JsonNode to : summary.get("To")) {
                match |= to.get("Address").asText().equalsIgnoreCase(address);
            }
            if (match) {
                result.add(0, message(summary.get("ID").asText()));
            }
        }
        return result;
    }

    /** Waits until exactly {@code count} messages were sent to {@code address}; returns them, oldest first. */
    List<Message> awaitCount(String address, int count) {
        List<List<Message>> found = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            List<Message> messages = to(address);
            assertThat(messages).as("emails to " + address).hasSize(count);
            found.add(messages);
        });
        return found.get(found.size() - 1);
    }

    /** The newest message to {@code address}, after waiting for the {@code count}-th one. */
    Message awaitLatest(String address, int count) {
        List<Message> messages = awaitCount(address, count);
        return messages.get(messages.size() - 1);
    }

    private Message message(String id) {
        JsonNode m = get("/api/v1/message/" + id);
        JsonNode from = m.get("From");
        List<String> to = new ArrayList<>();
        m.get("To").forEach(t -> to.add(t.get("Address").asText()));
        return new Message(id, from.get("Name").asText() + " <" + from.get("Address").asText() + ">", to,
                m.get("Subject").asText(), m.get("Text").asText(), m.get("HTML").asText());
    }

    private JsonNode get(String path) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(
                    "http://" + container.getHost() + ":" + container.getMappedPort(HTTP) + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            return json.readTree(r.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
