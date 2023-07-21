package org.permmath.rendering;

import static java.lang.ClassLoader.getSystemResource;
import static java.nio.file.Files.readString;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.xhtmlrenderer.util.FSImageWriter.newJpegWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.validation.constraints.NotBlank;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.permmath.PermMathProperties;
import org.permmath.validation.ValidImageWidth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestParam;
import org.xhtmlrenderer.swing.Java2DRenderer;


@Validated
@Service
public class ImageRendererService {

    @Autowired
    private PermMathProperties permMathProperties;

    public InputStreamResource getImage(
            @NotBlank @RequestParam String formula,
            @Nullable @ValidImageWidth @RequestParam(required = false) Integer imageWidht,
            @Nullable List<@NotBlank String> styleRefs,
            @RequestParam String style) throws IOException, URISyntaxException {

        // String.replace is deliberately used instead of templating engine.

        var styleRefLinks = Optional.ofNullable(styleRefs)
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .map(sr -> String.format("<link href=\"%s\" rel=\"stylesheet\" crossorigin=\"anonymous\" />", sr))
                .collect(joining("\n    "));

        var html = readString(
                    Paths.get(
                            getSystemResource("wrapper.html")
                        .toURI()))
                .replace("_*{STYLE_REFS}*_", styleRefLinks)
                .replace("_*{STYLE}*_", defaultIfNull(style, EMPTY))
                .replace("_*{FORMULA}*_", formula);

        var document = new W3CDom().fromJsoup(Jsoup.parse(html));
        var renderer = new Java2DRenderer(document, defaultIfNull(imageWidht, permMathProperties.getDefaultImageWidth()));
        renderer.getSharedContext().setDPI(permMathProperties.getDefaultDpi());

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        newJpegWriter(1.0f).write(renderer.getImage(), os);

        return new InputStreamResource(new ByteArrayInputStream(os.toByteArray()));
    }
}
