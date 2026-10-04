package io.replaydock;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** External destinations require exact HTTPS origin approval; redirects are never followed. */
@Component
public class TargetPolicy {
    private final Set<String> origins;
    public TargetPolicy(@Value("${replaydock.allowed-origins:}") String allowed) {
        origins = Arrays.stream(allowed.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> origin(URI.create(s))).collect(Collectors.toUnmodifiableSet());
    }
    public String validate(String target) {
        if (target == null || target.isBlank()) return null;
        try {
            URI uri = URI.create(target.trim());
            if (target.length() > 2048 || !"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null || !origins.contains(origin(uri))) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destination must use an explicitly allowed HTTPS origin");
        }
    }
    private static String origin(URI uri) {
        if (uri.getHost() == null) throw new IllegalArgumentException("Origin requires a hostname");
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
    }
}
