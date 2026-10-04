package io.replaydock;

import static io.replaydock.Models.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeliveryWorker {
    private final DockService dock;
    private final boolean enabled;
    private final int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public DeliveryWorker(DockService dock, @Value("${replaydock.worker-enabled:true}") boolean enabled,
                          @Value("${server.port:8080}") int port) { this.dock = dock; this.enabled = enabled; this.port = port; }
    @EventListener(ApplicationReadyEvent.class) public void recover() { if (enabled) dock.recoverInterrupted(); }
    @Scheduled(fixedDelay = 250) public void tick() { if (enabled) deliverOne(); }
    public void deliverOne() {
        Event event = dock.claimDue();
        if (event == null) return;
        Endpoint endpoint = dock.endpoint(event.endpointId());
        String target = endpoint.targetUrl() == null ? "http://127.0.0.1:" + port + "/mock/receiver/" + endpoint.id() : endpoint.targetUrl();
        long start = System.nanoTime();
        DeliveryResult result;
        try {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-ReplayDock-Event-Id", event.sourceId())
                    .header("X-ReplayDock-Timestamp", timestamp)
                    .header("X-ReplayDock-Signature", Signatures.sign(dock.secret(endpoint.id()), timestamp, event.sourceId(), event.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(event.payload())).build();
            // Discard untrusted response bodies instead of retaining potentially sensitive or unbounded content.
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            result = new DeliveryResult(response.statusCode(), "Receiver returned HTTP " + response.statusCode(), elapsed(start));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result = new DeliveryResult(null, "Delivery interrupted; outcome unknown", elapsed(start));
        } catch (java.io.IOException ex) {
            result = new DeliveryResult(null, ex instanceof java.net.http.HttpTimeoutException ? "Receiver timed out; outcome unknown" : "Connection failed", elapsed(start));
        }
        dock.complete(event, result);
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
