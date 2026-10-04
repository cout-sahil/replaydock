package io.replaydock;

import static io.replaydock.Models.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ApiController {
    private final DockService dock;
    public ApiController(DockService dock) { this.dock = dock; }
    @GetMapping("/api/session") public Map<String, String> session(CsrfToken token) {
        return Map.of("csrfHeader", token.getHeaderName(), "csrfToken", token.getToken(), "username", "admin");
    }
    @GetMapping("/api/endpoints") public List<Endpoint> endpoints() { return dock.endpoints(); }
    @PostMapping("/api/endpoints") @ResponseStatus(HttpStatus.CREATED)
    public CreatedEndpoint create(@RequestBody NewEndpoint input) { return dock.createEndpoint(input); }
    @GetMapping("/api/events") public List<Event> events() { return dock.events(); }
    @GetMapping("/api/events/{id}") public Event event(@PathVariable String id) { return dock.event(id); }
    @GetMapping("/api/events/{id}/attempts") public List<Attempt> attempts(@PathVariable String id) { return dock.attempts(id); }
    @PostMapping("/api/events/{id}/replay") public Event replay(@PathVariable String id) { return dock.replay(id); }
    @PostMapping("/api/endpoints/{id}/demo") @ResponseStatus(HttpStatus.ACCEPTED)
    public Receipt demo(@PathVariable String id, @RequestBody DemoEvent event) { return dock.accept(id, event.sourceId(), event.payload()); }
    @GetMapping("/api/endpoints/{id}/mock") public MockState mock(@PathVariable String id) { return dock.mockState(id); }
    @PutMapping("/api/endpoints/{id}/mock") public MockState configure(@PathVariable String id, @RequestBody MockConfig config) { return dock.configureMock(id, config); }
    @PostMapping("/hooks/{id}") public ResponseEntity<Receipt> ingest(@PathVariable String id, HttpServletRequest request) throws IOException {
        String body = signedBody(id, request);
        Receipt receipt = dock.accept(id, request.getHeader("X-ReplayDock-Event-Id"), body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(receipt);
    }
    @PostMapping("/mock/receiver/{id}") public ResponseEntity<Map<String, String>> receiver(@PathVariable String id, HttpServletRequest request) throws IOException, InterruptedException {
        signedBody(id, request);
        int delay = dock.mockState(id).delayMs();
        if (delay > 0) Thread.sleep(delay);
        MockResult result = dock.processMock(id, request.getHeader("X-ReplayDock-Event-Id"));
        return ResponseEntity.status(result.status()).body(Map.of("message", result.message()));
    }
    private String signedBody(String id, HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(65537);
        if (bytes.length > 65536) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        String body;
        try { body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UTF-8 body required"); }
        if (!Signatures.valid(dock.secret(id), request.getHeader("X-ReplayDock-Timestamp"), request.getHeader("X-ReplayDock-Event-Id"), body,
                request.getHeader("X-ReplayDock-Signature"), Instant.now().getEpochSecond())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired signature");
        }
        return body;
    }
}
