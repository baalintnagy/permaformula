package org.permaformula.rendering;

import static org.permaformula.validation.QuickValidator.validate;

import com.google.common.hash.Hashing;
import org.permaformula.contract.ComplexityPolicy;

/** Bind opaque token lookups to the same renderer and security versions as content keys. */
public final class UbcqIdentity {
    public static final String HTML =
        """
        html-svg-v13:input-validation-1:complexity-policy-2:mathjax-4.1.3:html-to-image-1.11.13:\
        presentation-policy-5:%s\
        """
        .formatted(
            SvgOutputPolicy.VERSION);
    public static final String FORMULA =
        "formula-svg-v7:input-validation-1:complexity-policy-2:mathjax-4.1.3:%s"
        .formatted(
            SvgOutputPolicy.VERSION);

    private UbcqIdentity() {
    }

    public static String context(String kind, int defaultWidth) {
        validate(
            !(!kind.equals("html")
                && !kind.equals("formula")),
            "Unknown image kind");
        return "ubcq-v1:%s:%s:%s".formatted((kind.equals("html") ? HTML : FORMULA),
            ComplexityPolicy.CURRENT.fingerprint(),
            defaultWidth);
    }

    public static String key(String context, String token) {
        return Hashing.sha256()
            .newHasher()
            .putUnencodedChars(context)
            .putChar('\n')
            .putUnencodedChars(token)
            .hash()
            .toString();
    }
}
