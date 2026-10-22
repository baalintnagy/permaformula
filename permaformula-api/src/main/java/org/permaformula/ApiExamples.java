package org.permaformula;

/** Shared, executable examples for the public API documentation. */
public final class ApiExamples {
    public static final String FORMULA = "sqrt(x^2 + y^2)";
    public static final String UBCQ =
        "H4sIAAAAAAACCqtWSssvKs3NSVSyUkqoiDNKUNJRysxNTE8Nz0wpyVCyMjIyqAUAxqPtbyQAAAA=";
    public static final String DESCRIPTOR =
        """
        {"forumla":"`x^2`","imageWidth":220}
        """;
    public static final String STYLESHEET =
        "https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css";
    public static final String STYLESHEET_LIST =
        """
        ["https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css"]\
        """;
    public static final String STYLESHEET_DESCRIPTION =
        """
        Optional external HTTPS stylesheet URLs in application order. Repeat styleRefs for \
        multiple stylesheets. Example: \
        https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css. \
        Stored by /ubcq; fetched with SSRF checks and validated by the HTML \
        rendering endpoints. Leave the example list empty to use only inline CSS.\
        """;
    public static final String UBCQ_DESCRIPTION =
        """
        Raw GZIP/Base64 descriptor returned by /ubcq, without a URL or ubcq= prefix. Paste the raw \
        value into Swagger; it URL-encodes the query parameter. The example contains `x^2` at \
        220px. For styled HTML, first execute /ubcq with its sum-of-cubes HTML/CSS examples and \
        copy the response here.\
        """;

    public static final String LIBRARY_ID =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    public static final String LIBRARY_CURSOR =
        "1791540000000.0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    public static final String LIBRARY_ID_DESCRIPTION =
        """
        64-character lowercase hexadecimal cache identity returned by /library-items. The example \
        is illustrative; first render an image, execute /library-items and copy an actual id to \
        try this endpoint.\
        """;
    public static final String LIBRARY_FORMULA =
        """
        {"id":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
         "kind":"formula","descriptor":{"forumla":"sqrt(x^2 + y^2)"},"createdAt":1791540000000}
        """;
    public static final String LIBRARY_HTML =
        """
        {"id":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
         "kind":"html","descriptor":{"forumla":"<p>`x^2`</p>","imageWidth":500,
         "style":"p { color: #263b54; }","styleRefs":["https://example.com/style.css"]},\
        "createdAt":1791540000000}
        """;
    public static final String LIBRARY_PAGE =
        """
        {"items":[
          {"id":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","kind":\
        "formula","excerpt":"sqrt(x^2 + y^2)","descriptor":{"forumla":"sqrt(x^2 + y^2)"},\
        "createdAt":1791540000000},
          {"id":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789","kind":"html",\
        "excerpt":"<p>`x^2`</p>","descriptor":{"forumla":"<p>`x^2`</p>","style":"p { color: red; \
        }","imageWidth":500},"createdAt":1791540000000}
        ],"nextCursor":null}
        """;

    private ApiExamples() {
    }
}
