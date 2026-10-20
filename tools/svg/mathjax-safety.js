// Use MathJax's discovered expressions and compiled MML tree; no delimiter/math lexer.
import { limits, validateMathInput, validateTree } from './complexity-limits.js';
import { Styles } from '@mathjax/src/js/util/Styles.js';
const BEFORE_COMPILE = 15;
const AFTER_COMPILE = 25;
const INVALID_NODES = Symbol('PermaFormula invalid math attributes');

export function safeMathOptions({ discoverInputs = true } = {}) {
    return {
        safeOptions: {
            allow: { URLs: 'safe', classes: 'safe', cssIDs: 'safe', styles: 'safe' },
            safeProtocols: {
                https: true,
                http: false,
                file: false,
                javascript: false,
                data: false,
            },
        },
        renderActions: {
            ...(discoverInputs
                ? { checkMathInputs: [BEFORE_COMPILE, validateInputs, validateInput] }
                : {}),
            validateCompiledMath: [AFTER_COMPILE, validateDocument, validateItem],
        },
    };
}

function validateInputs(document) {
    let count = 0;
    for (const math of document.math) {
        if (++count > limits.MATH_EXPRESSIONS) {
            throw new Error('Math expression count exceeds limit');
        }
        validateInput(math);
    }
}

function validateInput(math) {
    validateMathInput(math.math);
}

function validateDocument(document) {
    let nodes = 0;
    for (const math of document.math) {
        nodes += validateMath(math, document);
        if (nodes > limits.MATH_NODES) {
            throw new Error('Math document exceeds complexity limits');
        }
    }
}

function validateMath(math, document) {
    const count = validateTree(
        math.root,
        (node) => node.childNodes,
        limits.MATH_NODES,
        limits.MATH_DEPTH,
        'Compiled math',
    );
    // Native parser errors (including TeX expansion limits) reject rather than export an error image.
    math.root.walkTree((node) => {
        if (node.kind === 'merror' || document.safe[INVALID_NODES]?.has(node)) {
            throw new Error('Invalid math expression');
        }
        const attributes = node.attributes?.getAllAttributes();
        if (!attributes) {
            return;
        }
        // MathJax's protocol matcher does not account for embedded URL controls.
        // Reject before output rather than letting the API's SVG boundary fail with an IO error.
        for (const name of ['href', 'src', 'altimg']) {
            // eslint-disable-next-line no-control-regex -- URL controls must be rejected explicitly.
            if (attributes[name] && /[\u0000-\u0020\u007f]/u.test(attributes[name])) {
                throw new Error('Invalid math URL');
            }
        }
        // The unicode extension copies fontfamily into output CSS outside the style filter.
        // Accept family names/lists, but reject declaration and escape syntax.
        if (
            attributes.fontfamily &&
            // eslint-disable-next-line no-control-regex -- Reject controls in generated CSS values.
            /[;:{}()\\<>\u0000-\u001f\u007f]/u.test(attributes.fontfamily)
        ) {
            throw new Error('Invalid math font family');
        }
    });
    document.safe.sanitize(math, document);
    math.root.walkTree((node) => {
        if (node.kind === 'merror' || document.safe[INVALID_NODES]?.has(node)) {
            throw new Error('Invalid math expression');
        }
    });
    return count;
}

function validateItem(math, document) {
    validateMath(math, document);
}

export function installMathValidation(document) {
    const safe = document.safe;
    const invalidNodes = new WeakSet();
    safe[INVALID_NODES] = invalidNodes;
    const validateNode = safe.sanitizeNode.bind(safe);
    safe.sanitizeNode = (node) => {
        try {
            const attributes = node.attributes.getAllAttributes();
            const original = { ...attributes };
            const expected = canonicalAttributes(original, document.adaptor);
            validateNode(node);
            if (canonicalAttributes(attributes, document.adaptor) !== expected) {
                throw new Error('Invalid math attributes');
            }
            Object.assign(attributes, original);
        } catch (error) {
            // TeX post-filters catch sanitizer exceptions and can restore the original root.
            // Retain the rejection on the node so the post-compile action cannot lose it.
            invalidNodes.add(node);
            throw error;
        }
    };
}

function canonicalAttributes(attributes, adaptor) {
    return JSON.stringify(
        Object.entries(attributes)
            .map(([name, value]) => {
                if (name === 'style') {
                    const node = adaptor.node('div', { style: value });
                    const styles = new Styles(adaptor.allStyles(node)).styles;
                    return [name, Object.entries(styles).sort()];
                }
                if (name === 'class') {
                    return [name, String(value).trim().split(/\s+/).sort()];
                }
                return [name, String(value)];
            })
            .sort(([left], [right]) => left.localeCompare(right)),
    );
}
