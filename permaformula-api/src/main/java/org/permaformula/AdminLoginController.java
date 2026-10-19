package org.permaformula;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.permaformula.ratelimit.ClientRateLimits;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class AdminLoginController {
    private final AuthenticationManager authentication;
    private final JwtEncoder encoder;
    private final ClientRateLimits limits;

    public record Login(@NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 72) String password) {
    }

    public record Token(String accessToken, String tokenType, long expiresIn) {
    }

    @PostMapping("/admin/login")
    public ResponseEntity<Token> login(@Valid @RequestBody Login login,
        HttpServletRequest request) {
        try {
            Authentication user = limits.authenticate(request, () -> authentication.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(login.username(),
                    login.password())));
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder().issuer(AdminSecurityConfiguration.ISSUER)
                .subject(user.getName()).issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("scope", "admin").build();
            String token = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Token(token, "Bearer", 900));
        } catch (AuthenticationException _) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid username or password");
        }
    }
}
