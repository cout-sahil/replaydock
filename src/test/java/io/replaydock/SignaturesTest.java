package io.replaydock;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SignaturesTest {
    @Test void validatesExactBodyAndFreshTimestamp() {
        String signature = Signatures.sign("secret", "1000", "evt-1", "{\"hello\":\"world\"}");
        assertThat(Signatures.valid("secret", "1000", "evt-1", "{\"hello\":\"world\"}", signature, 1000)).isTrue();
        assertThat(Signatures.valid("secret", "1000", "evt-1", "{}", signature, 1000)).isFalse();
        assertThat(Signatures.valid("secret", "1000", "evt-2", "{\"hello\":\"world\"}", signature, 1000)).isFalse();
        assertThat(Signatures.valid("wrong", "1000", "evt-1", "{\"hello\":\"world\"}", signature, 1000)).isFalse();
        assertThat(Signatures.valid("secret", "1000", "evt-1", "{\"hello\":\"world\"}", signature, 1301)).isFalse();
        assertThat(Signatures.valid("secret", "invalid", "evt-1", "{}", signature, 1000)).isFalse();
        assertThat(Signatures.valid("secret", null, "evt-1", "{}", null, 1000)).isFalse();
    }
    @Test void exactOriginAllowlistRejectsCredentialsAndLookalikeHosts() {
        TargetPolicy policy = new TargetPolicy("https://receiver.example");
        assertThat(policy.validate("https://receiver.example/hooks")).isEqualTo("https://receiver.example/hooks");
        assertThatThrownBy(() -> policy.validate("https://receiver.example.attacker.test/hooks")).hasMessageContaining("400");
        assertThatThrownBy(() -> policy.validate("https://user:pass@receiver.example/hooks")).hasMessageContaining("400");
        assertThatThrownBy(() -> policy.validate("https://receiver.example:8443/hooks")).hasMessageContaining("400");
    }
}
