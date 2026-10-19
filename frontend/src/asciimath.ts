import { StreamLanguage } from '@codemirror/language';

export const asciiMath = StreamLanguage.define({
    token(stream) {
        if (stream.eatSpace()) {
            return null;
        }
        if (
            stream.match(
                /^(?:sqrt|root|frac|sum|prod|int|lim|sin|cos|tan|log|ln|abs|vec|hat|ubrace|obrace)\b/,
            )
        ) {
            return 'keyword';
        }
        if (stream.match(/^(?:alpha|beta|gamma|delta|theta|pi|omega|oo|NN|ZZ|QQ|RR|CC)\b/)) {
            return 'atom';
        }
        if (stream.match(/^\d+(?:\.\d+)?/)) {
            return 'number';
        }
        if (stream.match(/^"[^"]*"/)) {
            return 'string';
        }
        if (stream.match(/^[+\-*/=<>_^|!:]+/)) {
            return 'operator';
        }
        if (stream.match(/^[()[\]{},]/)) {
            return 'bracket';
        }
        stream.next();
        return null;
    },
});
