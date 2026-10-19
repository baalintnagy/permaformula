import assert from 'node:assert/strict';
import test from 'node:test';

test('rejects absent or malformed injected rendering policies at startup', async () => {
    for (const [index, policy] of [undefined, null, false, 42, 'policy'].entries()) {
        globalThis.PermaFormulaComplexity = policy;
        await assert.rejects(import(`./complexity-limits.js?invalid=${index}`), {
            message: 'Missing rendering complexity policy',
        });
    }
    delete globalThis.PermaFormulaComplexity;
});

test('enforces input, tree and geometry boundaries with the injected policy', async () => {
    globalThis.PermaFormulaComplexity = {
        MATH_CHARS: 3,
        RENDER_DIMENSION: 10,
        RENDER_AREA: 50,
    };
    const { limits, validateMathInput, validateTree, validateDimensions } =
        await import('./complexity-limits.js?valid');
    delete globalThis.PermaFormulaComplexity;
    assert.ok(Object.isFrozen(limits));
    validateMathInput('x+y');
    assert.throws(() => validateMathInput('x+y+z'), /size limit/);

    const tree = { children: [{ children: [{ children: [] }] }] };
    const children = (node) => node.children;
    assert.equal(validateTree(tree, children, 3, 2, 'Test tree'), 3);
    assert.throws(() => validateTree(tree, children, 2, 2, 'Test tree'), /complexity limits/);
    assert.throws(() => validateTree(tree, children, 3, 1, 'Test tree'), /complexity limits/);

    validateDimensions(10, 5);
    for (const dimensions of [
        [0, 1],
        [-1, 1],
        [NaN, 1],
        [Infinity, 1],
        [11, 1],
        [10, 6],
    ]) {
        assert.throws(() => validateDimensions(...dimensions), /dimensions exceed limits/);
    }
});
