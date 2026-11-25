package com.careernav.auth;

import com.careernav.entity.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class JwtService {
    private final byte[] secret;
    private final long tokenSeconds;

    public JwtService(@Value("${app.auth.secret:careernav-development-secret-change-me}") String secret,
                      @Value("${app.auth.token-hours:168}") long tokenHours) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tokenSeconds = Math.max(1, tokenHours) * 3600;
    }

    public String issue(UserEntity user) {
        long expires = Instant.now().getEpochSecond() + tokenSeconds;
        String payload = user.getId() + "|" + user.getUsername() + "|" + user.getRole() + "|" + expires;
        String encoded = b64(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + signature(encoded);
    }

    public Optional<TokenPayload> parse(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) return Optional.empty();
        if (!constantTimeEquals(signature(parts[0]), parts[1])) return Optional.empty();
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = payload.split("\\|", 4);
            if (fields.length != 4) return Optional.empty();
            long expires = Long.parseLong(fields[3]);
            if (expires < Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(new TokenPayload(Long.parseLong(fields[0]), fields[1], fields[2], expires));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return b64(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Token signing failed", exception);
        }
    }

    private String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8), b = right.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int index = 0; index < a.length; index++) diff |= a[index] ^ b[index];
        return diff == 0;
    }
    public record TokenPayload(Long userId, String username, String role, long expiresAt) {}
}
