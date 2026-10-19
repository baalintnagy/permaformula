import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.microsoft.playwright.*;
import org.owasp.validator.css.CssScanner;
import org.owasp.validator.html.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class AntiSamyTrial {
    record Case(String name, String css, String required) {
    }

    static final List<Case> CASES = List.of(
        new Case("Basic color", "p {color:red}", "color:red"),
        new Case("Custom property", ":root {--accent:255,0,0}", "--accent:255,0,0"),
        new Case("Variable reference", "p {color:rgba(var(--accent),1)}", "var(--accent)"),
        new Case("Calc arithmetic", "p {width:calc(100% - 2px)}", "calc("),
        new Case("Flex", "p {display:flex;gap:12px}", "display:flex"),
        new Case("Grid", "p {display:grid;grid-template-columns:repeat(2,1fr)}", "repeat("),
        new Case("Responsive media", "@media (min-width:200px) {p {color:red}}", "@media"),
        new Case("Supports", "@supports (display:grid) {p {display:grid}}", "@supports"),
        new Case("Cascade layer", "@layer theme {p {color:red}}", "@layer"),
        new Case("Nested selector", ".parent {color:red; & .child {color:blue}}", "&"),
        new Case("Gradient", "p {background-image:linear-gradient(red,blue)}", "linear-gradient"),
        new Case("SVG fragment paint", "p {fill:url(#glyph)}", "url(#glyph)"),
        new Case("Assistive MathML clip", "p {clip:rect(1px,1px,1px,1px)}", "rect("));

    public static void main(String[] args) throws Exception {
        InternalPolicy defaults = (InternalPolicy) Policy.getInstance();
        DocumentBuilderFactory builder = DocumentBuilderFactory.newDefaultInstance();
        builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = builder.newDocumentBuilder()
            .parse(Policy.class.getResourceAsStream("/antisamy.xml"));
        Node rules = doc.getElementsByTagName("css-rules").item(0);
        // Diagnostic only: permissive values separate parser limitations from default-policy
        // exclusions.
        // This policy is deliberately unsuitable for production or security claims.
        while (rules.hasChildNodes()) {
            rules.removeChild(rules.getFirstChild());
        }
        List.of("--accent", "color", "width", "display", "gap", "grid-template-columns",
            "background-image", "fill", "clip").forEach(name -> {
                Element property = doc.createElement("property");
                property.setAttribute("name", name);
                Element list = doc.createElement("regexp-list");
                Element regex = doc.createElement("regexp");
                regex.setAttribute("value", "[\\s\\S]*");
                list.appendChild(regex);
                property.appendChild(list);
                rules.appendChild(property);
            });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TransformerFactory.newDefaultInstance().newTransformer().transform(new DOMSource(doc),
            new StreamResult(output));
        Files.write(Path.of("target/diagnostic-policy.xml"), output.toByteArray());
        InternalPolicy permissive = (InternalPolicy) Policy
            .getInstance(new ByteArrayInputStream(output.toByteArray()));
        ResourceBundle messages = ResourceBundle.getBundle("AntiSamy", Locale.US);
        CASES.forEach(item -> {
            List.of(defaults, permissive).forEach(policy -> {
                String mode = policy == defaults ? "default" : "diagnostic";
                try {
                    CleanResults result = new CssScanner(policy, messages).scanStyleSheet(item.css,
                        1_048_576);
                    String cleaned = result.getCleanHTML().replaceAll("\\s+", "");
                    System.out.println(item.name + " | " + mode + " | "
                        + (cleaned.contains(item.required) ? "TOKEN_PRESENT" : "LOST") + " | "
                        + cleaned);
                } catch (Exception error) {
                    System.out.println(item.name + " | " + mode + " | ERROR | "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
                }
            });
        });
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
            "PLAYWRIGHT_BROWSERS_PATH",
            Path.of("../../.runtime/browsers").toAbsolutePath().normalize().toString(),
            "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setExecutablePath(Path.of(playwright.chromium().executablePath()))
                .setHeadless(true))) {
            Page page = browser.newPage();
            for (String css : List.of("p {display:grid;grid-template-columns:repeat(2,1fr)}",
                "p {background-image:linear-gradient(red,blue)}")) {
                String sanitized = new CssScanner(permissive, messages)
                    .scanStyleSheet(css, 1_048_576).getCleanHTML();
                List.of(css, sanitized).forEach(candidate -> {
                    page.setContent("<style>" + candidate + "</style><p>x</p>");
                    System.out.println("Browser | "
                        + (candidate.equals(css) ? "original" : "AntiSamy") + " | "
                        + page.evaluate(
                            """
                                ({grid:getComputedStyle(document.querySelector('p')).gridTemplateColumns, background:getComputedStyle(document.querySelector('p')).backgroundImage})\
                                """));
                });
            }
        }
    }
}
