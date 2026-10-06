package org.permmath.delivery;

import java.util.List;

import org.permmath.PermMathProperties;
import org.permmath.ratelimit.ClientRateLimitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.google.common.net.InetAddresses;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Enforce the delivery boundary after MVC chooses the handler, independent of URI normalization. */
@Configuration(proxyBeanMethods = false)
public class ImageDeliveryBoundary implements WebMvcConfigurer {
    private final ImageDeliveryProperties delivery;
    private final PermMathProperties origins;
    private final ClientRateLimitProperties clients;

    public ImageDeliveryBoundary(ImageDeliveryProperties delivery, PermMathProperties origins,
            ClientRateLimitProperties clients) {
        this.delivery = delivery;
        this.origins = origins;
        this.clients = clients;
        if (delivery.required() && (origins.host().equals(origins.imageHost()) || clients.trustedProxies().isEmpty())) {
            throw new IllegalArgumentException("Required image delivery needs a separate image-host and trusted ingress IP");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (delivery.required()) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    if (!(handler instanceof HandlerMethod method)
                        || !method.hasMethodAnnotation(ImageDelivery.class)) {
                        return true;
                    }
                    String peer = request.getRemoteAddr();
                    var markers = request.getHeaders("X-Permmath-Image-Delivery");
                    if (InetAddresses.isInetAddress(peer)
                            && clients.trustedProxies().contains(InetAddresses.toAddrString(InetAddresses.forString(peer)))
                            && markers.hasMoreElements() && "1".equals(markers.nextElement()) && !markers.hasMoreElements()) {
                        return true;
                    }
                    response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
                    response.setHeader("Location", origins.imageHost() + request.getRequestURI()
                            + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
                    response.setHeader("Cache-Control", "no-store");
                    return false;
                }
            });
        }
    }

    @Bean
    OpenAPI deliveryOpenApi() {
        var api = new OpenAPI();
        // Protected deployments terminate TLS before nginx; do not infer a public API URL from internal HTTP.
        if (delivery.required()) {
            api.servers(List.of(new Server().url(origins.host())));
        }
        return api;
    }
}
