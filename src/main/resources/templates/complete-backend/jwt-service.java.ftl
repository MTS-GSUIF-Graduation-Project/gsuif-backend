<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Token codec only. Bearer requests must resolve the returned UUID to a current account. */
@Service
@Profile({"test", "prod"})
public final class JwtService {
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final String ISSUER = "urn:generated-app:${packageName}:${contractId}:${contractVersion}";
    private static final String AUDIENCE = "${packageName}";

    private final Clock clock;
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    @Autowired
    public JwtService(Environment environment) {
        this(environment, Clock.systemUTC());
    }

    /** Clock injection supports boundary tests without changing the runtime clock. */
    public JwtService(Environment environment, Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
        String configured = environment.getProperty("GENERATED_APP_JWT_SECRET");
        if (configured == null || configured.length() > 4096 || configured.isBlank())
            throw new IllegalStateException("Invalid generated-app JWT secret configuration");
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(configured);
            if (!Base64.getEncoder().encodeToString(keyBytes).equals(configured) || keyBytes.length < 32)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid generated-app JWT secret configuration");
        }
        var key = new SecretKeySpec(keyBytes, "HmacSHA256");
        java.util.Arrays.fill(keyBytes, (byte) 0);
        encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Time and required claims are checked below against the same injectable clock.
        decoder.setJwtValidator(jwt -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
    }

    public String issue(AuthenticatedAccount account) {
        if (account == null) throw new IllegalArgumentException("Account identity is required");
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(account.id().toString()).issuedAt(now).notBefore(now)
                .expiresAt(now.plus(LIFETIME)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public UUID validate(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH)
            throw invalid();
        try {
            Jwt jwt = decoder.decode(token);
            Instant now = clock.instant();
            Instant issued = jwt.getIssuedAt();
            Instant notBefore = jwt.getNotBefore();
            Instant expires = jwt.getExpiresAt();
            if (!ISSUER.equals(jwt.getClaimAsString("iss"))
                    || !List.of(AUDIENCE).equals(jwt.getAudience())
                    || issued == null || notBefore == null || expires == null
                    || issued.isAfter(now.plus(CLOCK_SKEW))
                    || notBefore.isAfter(now.plus(CLOCK_SKEW))
                    || !expires.isAfter(now.minus(CLOCK_SKEW))
                    || !expires.isAfter(issued)
                    || expires.isAfter(issued.plus(LIFETIME))) throw invalid();
            String subject = jwt.getSubject();
            if (subject == null) throw invalid();
            return UUID.fromString(subject);
        } catch (JwtException | IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private static BadCredentialsException invalid() {
        return new BadCredentialsException("Authentication failed");
    }
}
