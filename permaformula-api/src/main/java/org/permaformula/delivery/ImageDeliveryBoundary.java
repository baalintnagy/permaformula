package org.permaformula.delivery;

import java.util.Enumeration;
import java.util.List;

import com.google.common.net.InetAddresses;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.permaformula.PermaFormulaProperties;
import org.permaformula.ratelimit.ClientRateLimitProperties;
import org.permaformula.validation.QuickValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enforce the delivery boundary after MVC chooses the handler, independent of URI normalization.
 */
@Configuration(proxyBeanMethods = false)
public class ImageDeliveryBoundary implements WebMvcConfigurer {
    private final ImageDeliveryProperties delivery;
    private final PermaFormulaProperties origins;
    private final ClientRateLimitProperties clients;

    public ImageDeliveryBoundary(ImageDeliveryProperties delivery, PermaFormulaProperties origins,
        ClientRateLimitProperties clients) {
        this.delivery = delivery;
        this.origins = origins;
        this.clients = clients;
        QuickValidator.validate(
            !(delivery.separateOriginRequired()
                && (!delivery.required()
                    || origins.sameImageOrigin())),
            () -> QuickValidator.violation(
                "Separate-origin delivery requires the image delivery boundary and a distinct image-host"));
        QuickValidator.validate(
            !(delivery.required()
                && clients.trustedProxies().isEmpty()),
            () -> QuickValidator.violation("Required image delivery needs a trusted ingress IP"));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (delivery.required()) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                    Object handler) {
                    if (!(handler instanceof HandlerMethod method)
                        || !method.hasMethodAnnotation(ImageDelivery.class)) {
                        return true;
                    }
                    String peer = request.getRemoteAddr();
                    Enumeration<String> markers = request
                        .getHeaders("X-PermaFormula-Image-Delivery");
                    if (InetAddresses.isInetAddress(peer)
                        && clients.trustedProxies()
                            .contains(InetAddresses.toAddrString(InetAddresses.forString(peer)))
                        && markers.hasMoreElements()
                        && "1".equals(markers.nextElement())
                        && !markers.hasMoreElements()) {
                        return true;
                    }
                    response.setHeader("Cache-Control", "no-store");
                    if (origins.sameImageOrigin()) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        return false;
                    }
                    response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
                    response.setHeader("Location", origins.imageHost() + request.getRequestURI()
                        + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
                    return false;
                }
            });
        }
    }

    @Bean
    OpenAPI deliveryOpenApi() {
        OpenAPI api = new OpenAPI();
        // Protected deployments terminate TLS before nginx; do not infer a public API URL from
        // internal HTTP.
        if (delivery.required()) {
            api.servers(List.of(new Server().url(origins.host())));
        }
        return api;
    }
}
