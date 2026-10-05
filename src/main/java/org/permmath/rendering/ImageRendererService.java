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
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.permmath.PermMathProperties;
import org.permmath.url.ImageDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.xhtmlrenderer.swing.Java2DRenderer;

import jakarta.annotation.PostConstruct;


@Validated
@Service
public class ImageRendererService {

    @Autowired
    private PermMathProperties permMathProperties;

    @Autowired
    private AsciiMathExecutor asciiMathExecutor;

    private String wrapperHtmlTemplate;

    @PostConstruct
    void init() throws IOException, URISyntaxException {
        wrapperHtmlTemplate = readString(
                Paths.get(
                        getSystemResource("wrapper.html")
                                .toURI()));
    }

    public InputStreamResource getImage(@Valid @NotNull ImageDescriptor imageDescriptor) throws IOException {

        // String.replace is deliberately used instead of templating engine.
        // (Security sucks at the moment!)

        var styleRefLinks = Optional.ofNullable(imageDescriptor.styleRefs())
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .map(sr -> String.format("<link href=\"%s\" rel=\"stylesheet\" crossorigin=\"anonymous\" />", sr))
                .collect(joining("\n    "));

        var html = wrapperHtmlTemplate
                .replace("_*{STYLE_REFS}*_", styleRefLinks)
                .replace("_*{STYLE}*_", defaultIfNull(imageDescriptor.style(), EMPTY))
                .replace("_*{FORMULA}*_", imageDescriptor.forumla());

        var document = new W3CDom().fromJsoup(Jsoup.parse(html));
        // asciiMathExecutor.processAsciiMath(document);

        var renderer = new Java2DRenderer(document,
                defaultIfNull(imageDescriptor.imageWidth(), permMathProperties.defaultImageWidth()));
        renderer.getSharedContext().setDPI(permMathProperties.defaultDpi());

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        newJpegWriter(1.0f).write(renderer.getImage(), os);

        return new InputStreamResource(new ByteArrayInputStream(os.toByteArray()));
    }
}
