package org.permaformula;

import java.sql.SQLException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.permaformula.rendering.cache.JdbcImageCache;
import org.permaformula.rendering.cache.JdbcImageCache.LibraryCursor;
import org.permaformula.rendering.cache.JdbcImageCache.LibraryItem;
import org.permaformula.rendering.cache.JdbcImageCache.LibrarySummary;
import org.permaformula.url.ImageDescriptor;
import org.permaformula.validation.CacheId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Tag(name = "Image library",
    description =
        """
        Browse publicly shared persisted SVGs and restore their original sources in the Builder. \
        Successful SVG rendering publishes an entry; creating a UBCQ alone does not. JPEG \
        conversions share their source SVG entry. The library follows cache retention: expired, \
        evicted and metadata-free rows are excluded. Memory-only deployments have an empty library.\
        """
)
@ApiResponses({
        @ApiResponse(responseCode = "400",
            description = "Missing or malformed id, or pagination outside the permitted range.",
            content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "429",
            description = "Client request quota exhausted. Retry-After indicates when to retry.",
            content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Persistent cache could not be queried.",
            content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = ProblemDetail.class)))
})
@RestController
@RequiredArgsConstructor
public class LibraryController {
    private final ObjectProvider<JdbcImageCache> caches;
    private final ObjectMapper mapper;

    @Schema(
        description =
            """
            A persisted SVG and its original decoded source descriptor. Source content is public \
            and has not been sanitized for editing. descriptor contains the decoded original \
            source; HTML entries record effective canvas width, while formula entries omit layout \
            and styling.\
            """
    )
    public record Item(
        @Schema(
            description =
                """
                Cache identity used by /library-item. Images use UBCQ URLs. Copy an actual id from \
                the list response.\
                """
            ,
            example = ApiExamples.LIBRARY_ID, pattern = "^[a-f0-9]{64}$") String id,
        @Schema(
            description =
                """
                Builder editor to restore. formula uses descriptor.forumla as plain AsciiMath; \
                html restores HTML, CSS, stylesheet order and canvas width.\
                """
            ,
            allowableValues = {
                    "formula", "html" },
            example = "html") String kind,
        ImageDescriptor descriptor,
        @Schema(description = "Creation time in Unix epoch milliseconds, using the database clock.",
            example = "1791540000000") long createdAt,
        @Schema(
            description =
                "Stored GZIP/Base64 descriptor for the existing image links.") String ubcq) {
    }

    @Schema(
        description =
            """
            Public source entry including its stored UBCQ for image URLs. Full decoded descriptors \
            are included when aggregate decoded source fits 256 KiB. Otherwise excerpts are \
            returned; the UBCQ is still included.\
            """
    )
    public record Summary(String id, String kind, String excerpt, long createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) ImageDescriptor descriptor,
        @Schema(
            description =
                """
                Stored GZIP/Base64 descriptor. URL-encode as ubcq on the matching \
                html-svg/html-jpg endpoint for HTML entries. Formula entries use the original \
                forumla value as the formula parameter on exp-svg/exp-jpg.\
                """
        ) String ubcq) {
    }

    public record Page(List<Summary> items, String nextCursor) {
    }

    @Operation(summary = "Browse compact persisted image summaries",
        description =
            """
            Returns bounded excerpts ordered by creation time and cache identity descending. Pass \
            nextCursor unchanged as cursor to retrieve the next page; null means the end. An empty \
            items array means no entries (also returned when persistence is disabled). Full \
            descriptors are included when their combined serialized UTF-8 size is at most 256 KiB; \
            larger pages return excerpts. Each item includes its complete stored ubcq. Pages also \
            have a 2 MiB combined UBCQ budget, so may contain fewer than limit items. An entry \
            larger than that budget is returned alone. Follow nextCursor even on a short page. Use \
            /library-item to load omitted sources. Nonzero offset pagination is no longer \
            supported. Rendering and expiry can change the list between requests. Listing does not \
            refresh cache expiry or render images; normal UBCQ preview requests do. Responses use \
            Cache-Control: no-store. Example ids are illustrative; use ids from the live response \
            for the detail and SVG endpoints.\
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Page of public image summaries; at most limit entries.",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = Page.class),
            examples = {
                    @ExampleObject(name = "mixedPage", summary = "Formula and styled HTML entries",
                        value = ApiExamples.LIBRARY_PAGE),
                    @ExampleObject(name = "emptyPage", summary = "Empty cache or end of list",
                        value = "{\"items\":[],\"nextCursor\":null}") }))
    @GetMapping("/library-items")
    public ResponseEntity<Page> list(@Parameter(
        description =
            "Continuation cursor returned by the preceding page. Omit for the first page.",
        example = ApiExamples.LIBRARY_CURSOR) @RequestParam(
            required = false) String cursor,
        @Parameter(
            description =
                """
                Legacy first-page compatibility only; must be zero. Use cursor for subsequent \
                pages.\
                """
            ,
            example = "0") @Min(value = 0, message = "Invalid library page") @Max(value = 0,
                message = "Invalid library page") @RequestParam(defaultValue = "0") int offset,
        @Parameter(description = "Maximum entries to return, from 1 to 24. Defaults to 12.",
            example = "12",
            schema = @Schema(minimum = "1", maximum = "24", defaultValue = "12")) @RequestParam(
                defaultValue = "12") @Min(value = 1, message = "Invalid library page") @Max(
                    value = 24, message = "Invalid library page") int limit)
        throws SQLException {
        LibraryCursor continuation = parseCursor(cursor);
        JdbcImageCache cache = caches.getIfAvailable();
        List<LibrarySummary> rows = cache == null ? List.<JdbcImageCache.LibrarySummary>of()
            : cache.library(continuation, limit);
        List<Summary> items = rows.stream()
            .filter(row -> !row.lookahead())
            .limit(limit)
            .map(row -> new Summary(row.id(),
                row.kind(),
                row.excerpt(),
                row.createdAt(),
                row.descriptor() == null ? null
                    : mapper.readValue(row.descriptor(), ImageDescriptor.class),
                row.ubcq()))
            .toList();
        String next = rows.size() > items.size()
            ? "%s.%s".formatted(items.getLast()
                .createdAt(),
                items.getLast()
                    .id())
            : null;
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .body(new Page(items, next));
    }

    @Operation(summary = "Load an image's source for editing",
        description =
            """
            Returns the editor kind and decoded descriptor for one live persisted SVG. No \
            rendering or stylesheet fetching occurs. Use kind to select the Builder editor and \
            descriptor to restore its inputs. Responses use Cache-Control: no-store. An entry can \
            disappear after listing when it expires or is evicted.\
            """
    )
    @ApiResponse(responseCode = "200", description = "Original source and editor metadata.",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = Item.class),
            examples = { @ExampleObject(name = "formula", value = ApiExamples.LIBRARY_FORMULA),
                    @ExampleObject(name = "styledHtml", value = ApiExamples.LIBRARY_HTML) }))
    @ApiResponse(responseCode = "404",
        description = "No live source-bearing entry with this id, or persistence is disabled.",
        content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/library-item")
    public ResponseEntity<Item> detail(
        @Parameter(description = ApiExamples.LIBRARY_ID_DESCRIPTION,
            example = ApiExamples.LIBRARY_ID,
            schema = @Schema(pattern = "^[a-f0-9]{64}$")) @CacheId(
                message = "Invalid library id") @RequestParam String id)
        throws SQLException {
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .body(item(require(id)));
    }

    private JdbcImageCache.LibraryItem require(String id) throws SQLException {
        JdbcImageCache cache = caches.getIfAvailable();
        LibraryItem entry = cache == null ? null
            : cache.libraryItem(id);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image expired or unavailable");
        }
        return entry;
    }

    private static JdbcImageCache.LibraryCursor parseCursor(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > 84
            || !value.matches("[0-9]{1,19}\\.[a-f0-9]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid library cursor");
        }
        try {
            int separator = value.indexOf('.');
            return new JdbcImageCache.LibraryCursor(Long.parseLong(value.substring(0, separator)),
                value.substring(separator + 1));
        } catch (NumberFormatException _) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid library cursor");
        }
    }

    private Item item(JdbcImageCache.LibraryItem entry) {
        return new Item(entry.id(),
            entry.kind(),
            mapper.readValue(entry.descriptor(), ImageDescriptor.class),
            entry.createdAt(),
            entry.ubcq());
    }
}
