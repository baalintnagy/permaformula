package org.permaformula.rendering;

import static org.permaformula.validation.QuickValidator.coalesce;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.common.hash.Hashing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.permaformula.PermaFormulaProperties;
import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.ratelimit.ClientRateLimits;
import org.permaformula.rendering.cache.GeneratedImageStore;
import org.permaformula.url.ImageDescriptor;
import org.permaformula.url.UrlService;
import org.permaformula.validation.ValidDescriptor;
import org.permaformula.validation.ValidEncoded;
import org.permaformula.validation.ValidFormula;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

@Service
@Validated
@RequiredArgsConstructor
public class GeneratedImageService {
    private final UrlService urls;

    private final GeneratedImageStore store;

    private final ImageRendererService htmlRenderer;

    private final FormulaSvgRenderer formulaRenderer;

    private final PermaFormulaProperties defaults;

    private final ObjectMapper mapper;
    private final ApiWorkService work;

    public String getHtml(@ValidDescriptor @Valid @NotNull ImageDescriptor descriptor)
        throws IOException {
        return html(descriptor, null);
    }

    public String getHtmlUbcq(@ValidEncoded String ubcq) throws IOException {
        String token = tokenKey("html", ubcq);
        String hit = store.findByToken(token);
        return hit != null ? hit
            : html(urls.decodeUbcq(ubcq), token);
    }

    private String html(ImageDescriptor descriptor, String token) throws IOException {
        Prepared prepared = work.prepare(() -> {
            ImageDescriptor effective = new ImageDescriptor(descriptor.forumla(),
                coalesce(descriptor.imageWidth(), defaults::defaultImageWidth),
                coalesce(descriptor.styleRefs(), List::of),
                coalesce(descriptor.style(), ""));
            return new Prepared(effective, key(UbcqIdentity.HTML, effective));
        });
        String output = store.getOrGenerate(prepared.key(),
            () -> work.generate(ClientRateLimits.generation(() -> {
                try (InputStream input = htmlRenderer.getImage(prepared.descriptor())
                    .getInputStream()) {
                    return SvgOutputPolicy
                        .sanitize(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            })));
        store.describe(prepared.key(), "html",
            () -> mapper.writeValueAsString(prepared.descriptor()),
            prepared.descriptor()
                .forumla(),
            UbcqIdentity.context("html", defaults.defaultImageWidth()));
        store.rememberToken(token, prepared.key());
        return output;
    }

    public String getExp(@ValidFormula @NotBlank String formula) throws IOException {
        return formula(formula, null);
    }

    private String formula(String formula, String token) throws IOException {
        String cacheKey = work.prepare(() -> {
            ImageDescriptor identity = new ImageDescriptor(formula, null, null, null);
            return key(UbcqIdentity.FORMULA, identity);
        });
        String output = store.getOrGenerate(cacheKey,
            () -> work.generate(ClientRateLimits.generation(
                () -> SvgOutputPolicy.sanitize(formulaRenderer.render(formula)))));
        store.describe(cacheKey, "formula",
            () -> mapper.writeValueAsString(new ImageDescriptor(formula, null, null, null)),
            formula, UbcqIdentity.context("formula", defaults.defaultImageWidth()));
        store.rememberToken(token, cacheKey);
        return output;
    }

    private final RenderGateway gateway;

    public byte[] getExpJpg(@ValidFormula @NotBlank String formula) throws IOException {
        return jpeg(getExp(formula));
    }

    public byte[] getHtmlJpg(@ValidDescriptor @Valid @NotNull ImageDescriptor descriptor)
        throws IOException {
        return jpeg(getHtml(descriptor));
    }

    public byte[] getHtmlJpgUbcq(@ValidEncoded String ubcq) throws IOException {
        return jpeg(getHtmlUbcq(ubcq));
    }

    private String tokenKey(String kind, String ubcq) throws IOException {
        return work.prepare(() -> {
            return UbcqIdentity.key(UbcqIdentity.context(kind, defaults.defaultImageWidth()),
                ubcq);
        });
    }

    private byte[] jpeg(String svg) throws IOException {
        String cacheKey = work.prepare(() -> Hashing.sha256()
            .hashString(
                """
                jpeg-v1:chromium-1.59.0:scale-2:quality-95:white:%s
                %s\
                """
                    .formatted(ComplexityPolicy.CURRENT.fingerprint(), svg),
                StandardCharsets.UTF_8)
            .toString());
        String encoded = store.getOrGenerate(cacheKey,
            () -> work.generate(ClientRateLimits.generation(
                () -> JpegOutputPolicy.encode(gateway.renderJpeg(svg)))));
        return work.prepare(() -> JpegOutputPolicy.decode(encoded));
    }

    private String key(String format, ImageDescriptor descriptor) {
        // Stable versioned content identity, also suitable for filenames or database keys.
        String identity =
            """
            %s:%s
            %s\
            """
            .formatted(
                format,
                ComplexityPolicy.CURRENT.fingerprint(),
                mapper.writeValueAsString(descriptor));
        return Hashing.sha256()
            .hashString(identity, StandardCharsets.UTF_8)
            .toString();
    }

    private record Prepared(ImageDescriptor descriptor, String key) {
    }
}
