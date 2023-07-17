package org.permmath;

import static java.lang.ClassLoader.getSystemResource;
import static java.nio.file.Files.readString;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static org.apache.commons.lang3.StringUtils.EMPTY;

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
import org.jsoup.nodes.Document;
import org.springframework.core.io.InputStreamResource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestParam;
import org.xhtmlrenderer.swing.Java2DRenderer;
import org.xhtmlrenderer.util.FSImageWriter;


@Validated
@Service
public class PermMathRendererService {

    public InputStreamResource getImage(
            @NotBlank @RequestParam String formula,
            @Nullable List<@NotBlank String> styleRefs,
            @RequestParam String style) throws IOException, URISyntaxException {

        // String.replace is deliberately used instead of templating engine.

        var styleRefLinks = Optional.ofNullable(styleRefs)
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .map(sr -> String.format("<link href=\"%s\" crossorigin=\"anonymous\" />", sr))
                .collect(joining("\n"));

        var html = readString(Paths.get(getSystemResource("wrapper.html").toURI()))
                .replace("STYLE_REFS", styleRefLinks)
                .replace("STYLE", defaultIfNull(style, EMPTY))
                .replace("FORMULA", formula);
        var parsedHtml = Jsoup.parse(html);
        parsedHtml.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        var document = new W3CDom().fromJsoup(parsedHtml);

        // var document = XMLResource.load(new ByteArrayInputStream(html.getBytes())).getDocument();

        var renderer = new Java2DRenderer(document, 300);

        var imageWriter = FSImageWriter.newJpegWriter(1.0f);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        imageWriter.write(renderer.getImage(), os);
        var is = new ByteArrayInputStream(os.toByteArray());

        return new InputStreamResource(is);
    }
}
