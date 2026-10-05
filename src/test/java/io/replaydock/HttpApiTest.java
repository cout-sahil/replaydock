package io.replaydock;

import static io.replaydock.Models.*;
import static org.assertj.core.api.Assertions.*;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestDatabase.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "server.port=18089", "spring.datasource.url=jdbc:h2:mem:http-tests;DB_CLOSE_DELAY=-1",
    "replaydock.worker-enabled=false", "replaydock.admin-password=test-password"})
class HttpApiTest {
    @Autowired DockService dock;
    @Autowired DeliveryWorker worker;
    @Autowired TestDatabase db;
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private CreatedEndpoint created;
    @BeforeEach void setup() {
        db.clear();
        created = dock.createEndpoint(new NewEndpoint("HTTP test", null, 5, 100L));
    }
    @Test void signedIngressRejectsTamperingAndOversizedBodies() throws Exception {
        assertThat(ingress("evt-1", "{}", "wrong", Instant.now().getEpochSecond()).statusCode()).isEqualTo(401);
        assertThat(ingress("evt-1", "{}", created.signingSecret(), Instant.now().getEpochSecond() - 301).statusCode()).isEqualTo(401);
        assertThat(ingress("evt-1", "x".repeat(65537), created.signingSecret(), Instant.now().getEpochSecond()).statusCode()).isEqualTo(413);
        assertThat(dock.events()).isEmpty();
    }
    @Test void realHttpRetriesRecoverAndReplayIsIdempotent() throws Exception {
        HttpResponse<String> accepted = ingress("payment-1", "{}", created.signingSecret(), Instant.now().getEpochSecond());
        assertThat(accepted.statusCode()).isEqualTo(202);
        String id = json.readTree(accepted.body()).get("eventId").asString();
        assertThat(dock.event(id).status()).isEqualTo("PENDING");
        HttpResponse<String> duplicate = ingress("payment-1", "{}", created.signingSecret(), Instant.now().getEpochSecond());
        assertThat(json.readTree(duplicate.body()).get("duplicate").asBoolean()).isTrue();
        for (int i = 0; i < 3; i++) { db.makeDue(id); worker.deliverOne(); }
        assertThat(dock.event(id).status()).isEqualTo("DELIVERED");
        assertThat(dock.attempts(id)).extracting(Attempt::httpStatus).containsExactly(503, 503, 200);
        dock.replay(id); worker.deliverOne();
        assertThat(dock.event(id).status()).isEqualTo("DELIVERED");
        assertThat(dock.mockState(created.endpoint().id()).processedCount()).isEqualTo(1);
    }
    @Test void adminRequiresAuthenticationAndCsrfAndNeverListsSecrets() throws Exception {
        HttpResponse<String> anonymous = client.send(HttpRequest.newBuilder(uri("/api/endpoints")).header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpResponse<String> session = client.send(HttpRequest.newBuilder(uri("/api/session")).header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(session.statusCode()).isEqualTo(200);
        JsonNode token = json.readTree(session.body());
        String body = "{\"name\":\"Secure endpoint\"}";
        HttpResponse<String> noCsrf = client.send(HttpRequest.newBuilder(uri("/api/endpoints")).header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        HttpResponse<String> valid = client.send(HttpRequest.newBuilder(uri("/api/endpoints")).header("Authorization", auth).header("Content-Type", "application/json")
                .header(token.get("csrfHeader").asString(), token.get("csrfToken").asString()).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(valid.statusCode()).isEqualTo(201);
        HttpResponse<String> list = client.send(HttpRequest.newBuilder(uri("/api/endpoints")).header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(list.body()).doesNotContain(created.signingSecret()).doesNotContain("signingSecret");
    }
    private HttpResponse<String> ingress(String sourceId, String body, String secret, long seconds) throws Exception {
        String timestamp = Long.toString(seconds);
        return client.send(HttpRequest.newBuilder(uri("/hooks/" + created.endpoint().id())).header("Content-Type", "application/json")
                .header("X-ReplayDock-Event-Id", sourceId).header("X-ReplayDock-Timestamp", timestamp)
                .header("X-ReplayDock-Signature", Signatures.sign(secret, timestamp, sourceId, body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:18089" + path); }
}
