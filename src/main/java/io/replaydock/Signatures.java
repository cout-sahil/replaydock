package io.replaydock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** ReplayDock's signature format binds timestamp, event ID and exact UTF-8 body. */
public final class Signatures {
    private Signatures() {}
    public static String newSecret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    public static String sign(String secret, String timestamp, String eventId, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + eventId + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
    public static boolean valid(String secret, String timestamp, String eventId, String body, String signature, long nowSeconds) {
        if (timestamp == null || eventId == null || signature == null || signature.length() != 71) return false;
        try {
            long supplied = Long.parseLong(timestamp);
            if (supplied < nowSeconds - 300 || supplied > nowSeconds + 300) return false;
            return MessageDigest.isEqual(sign(secret, timestamp, eventId, body).getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException ex) { return false; }
    }
}
