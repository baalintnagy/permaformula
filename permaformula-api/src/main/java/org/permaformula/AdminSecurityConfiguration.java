package org.permaformula;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.permaformula.validation.QuickValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.*;

@Configuration
@EnableWebSecurity
public class AdminSecurityConfiguration {
    static final String ISSUER = "permaformula-admin";

    @Bean
    HttpFirewall adminFirewall() {
        StrictHttpFirewall strict = new StrictHttpFirewall();
        StrictHttpFirewall image = new StrictHttpFirewall();
        image.setAllowSemicolon(true);
        return new HttpFirewall() {
            @Override
            public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
                // Preserve the existing image-delivery redirect for matrix-parameter URLs.
                // Admin endpoints retain the strict firewall.
                String path = request.getRequestURI().substring(request.getContextPath().length());
                return (path.matches("/(exp|html)-(svg|jpg);[^/]*") ? image : strict)
                    .getFirewalledRequest(request);
            }

            @Override
            public HttpServletResponse getFirewalledResponse(HttpServletResponse response) {
                return strict.getFirewalledResponse(response);
            }
        };
    }

    @Bean
    AuthenticationManager adminAuthenticationManager(
        @Value("${permaformula.admin.password:}") String password) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
        QuickValidator.validate(
            !(password.getBytes(StandardCharsets.UTF_8).length > 72), () -> QuickValidator
                .violation("PERMAFORMULA_ADMIN_PASSWORD must be at most 72 UTF-8 bytes"));
        if (StringUtils.isNotBlank(password)) {
            users.createUser(User.withUsername("admin")
                .password(encoder.encode(password)).roles("ADMIN").build());
        }
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    // A new signing key on restart also invalidates previously issued logins.
    @Bean
    KeyPair adminSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    JwtEncoder adminJwtEncoder(KeyPair adminSigningKey) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) adminSigningKey.getPublic())
            .privateKey((RSAPrivateKey) adminSigningKey.getPrivate())
            .keyID(UUID.randomUUID().toString()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder adminJwtDecoder(KeyPair adminSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withPublicKey((RSAPublicKey) adminSigningKey.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    @Bean
    SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/admin/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/admin/login")
                .permitAll()
                .anyRequest().hasAuthority("SCOPE_admin"))
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
            .build();
    }
}
