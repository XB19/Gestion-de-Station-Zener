package tg.zener.station.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Jetons JWT signés HS256 (sub = identifiant de l'employé). */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper json;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-hours}") long ttlHours,
                      ObjectMapper json) {
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET doit contenir au moins 32 caractères");
        }
        if (secret.startsWith("dev-")) {
            log.warn("JWT_SECRET de développement utilisé : à remplacer en production");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = Duration.ofHours(ttlHours);
        this.json = json;
    }

    public String issue(long employeeId, boolean remember) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(employeeId));
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(remember ? ttl.multipliedBy(7) : ttl).getEpochSecond());
        try {
            String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = B64.encodeToString(json.writeValueAsBytes(claims));
            return header + "." + payload + "." + sign(header + "." + payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Identifiant de l'employé si le jeton est valide et non expiré, sinon null. */
    public Long verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            byte[] expected = sign(parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.US_ASCII))) {
                return null;
            }
            JsonNode claims = json.readTree(B64D.decode(parts[1]));
            if (claims.path("exp").asLong() < Instant.now().getEpochSecond()) {
                return null;
            }
            return Long.parseLong(claims.path("sub").asText());
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return B64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.US_ASCII)));
    }
}
