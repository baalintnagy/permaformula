package org.permaformula.rendering;

import com.google.common.hash.Hashing;
import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.validation.QuickValidator;

/** Bind opaque token lookups to the same renderer and security versions as content keys. */
public final class UbcqIdentity {
    public static final String HTML = "html-svg-v12:input-validation-1:complexity-policy-2:html-to-image-1.11.13:presentation-policy-4:"
        + SvgOutputPolicy.VERSION;
    public static final String FORMULA = "formula-svg-v6:input-validation-1:complexity-policy-2:mathjax-3.2.2:"
        + SvgOutputPolicy.VERSION;

    private UbcqIdentity() {
    }

    public static String context(String kind, int defaultWidth) {
        QuickValidator.validate(
            !(!kind.equals("html")
                && !kind.equals("formula")),
            () -> QuickValidator.violation("Unknown image kind"));
        return """
            ubcq-v1:%s:%s:%s\
            """.formatted((kind.equals("html") ? HTML : FORMULA),
            ComplexityPolicy.CURRENT.fingerprint(), defaultWidth);
    }

    public static String key(String context, String token) {
        return Hashing.sha256().newHasher().putUnencodedChars(context).putChar('\n')
            .putUnencodedChars(token).hash().toString();
    }
}
