const { assert, delay, request, status, svg, cli } = require('./test-utils.cjs');
async function main(args) {
    const base = args['base-url'];
    assert(
        ['localhost', '127.0.0.1', '[::1]'].includes(new URL(base).hostname),
        'Run large-input fixtures on a disposable localhost single-host Compose project only.',
    );
    let listening = false;
    for (let attempt = 0; attempt < 120; attempt++) {
        try {
            if ((await request(base + '/', {}, 'GET', 2000)).status === 200) {
                listening = true;
                break;
            }
        } catch {}
        await delay(500);
    }
    assert(listening, 'API did not become ready behind ingress.');
    // Cheap to render but beyond the old source limits; exercises real HTTP,
    // compression, decoding, sanitization and the isolated renderer together.
    const html = `<!--${'h'.repeat(4 * 1024 * 1024 + 128)}--><p>Large source, small render</p>`;
    const style = `/*${'c'.repeat(1024 * 1024 + 128)}*/ p { color: red; }`;
    const encoded = await request(`${base}/ubcq?${new URLSearchParams({ formula: html, style })}`);
    status(encoded, 200);
    const query = new URLSearchParams({ ubcq: encoded.text });
    const decoded = await request(`${base}/ubcq-decode?${query}`);
    status(decoded, 200);
    assert.equal(JSON.parse(decoded.text).forumla, html);
    assert.equal(JSON.parse(decoded.text).style, style);
    svg(await request(`${base}/html-svg?${query}`));
    svg(
        await request(
            `${base}/exp-svg?${new URLSearchParams({ formula: 'x' + ' '.repeat(300 * 1024) })}`,
        ),
    );
    for (const parameters of [
        { formula: 'x'.repeat(6 * 1024 * 1024 + 1) },
        { formula: 'x', style: 'x'.repeat(1536 * 1024 + 1) },
    ]) {
        status(await request(`${base}/ubcq?${new URLSearchParams(parameters)}`), 400);
    }
    status(
        await request(
            `${base}/exp-svg?${new URLSearchParams({ formula: 'x'.repeat(384 * 1024 + 1) })}`,
        ),
        400,
    );
    const stamp = Date.now();
    const concurrent = await Promise.all(
        [1, 2].map((number) =>
            request(`${base}/exp-svg?${new URLSearchParams({ formula: `x^${number}+${stamp}` })}`),
        ),
    );
    assert(
        concurrent.some((response) => response.status === 200),
        'No concurrent render completed.',
    );
    for (const response of concurrent) {
        if (response.status === 200) {
            svg(response);
        } else {
            status(response, 429);
            assert(response.headers['retry-after']);
        }
    }
    console.log(
        'PASS: enlarged HTML/CSS/formula inputs traverse HTTP and isolated rendering; oversized fields rejected.',
    );
}
cli(
    main,
    { 'base-url': 'http://localhost:5017' },
    'test-input-limits.sh [--base-url URL] (disposable localhost single-host deployment with docker/input-limits/smoke.override.yaml)',
);
