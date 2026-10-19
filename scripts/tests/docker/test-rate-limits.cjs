const { assert, follow: request, status, delay, cli } = require('./test-utils.cjs');
function limited(response) {
    status(response, 429);
    assert.match(response.headers['retry-after'] || '', /^[1-9]\d*$/);
    assert.equal(response.headers['cache-control'], 'no-store');
}
async function main(args) {
    const base = args['base-url'];
    let ready = false;
    for (let attempt = 0; attempt < 60; attempt++) {
        let response;
        try {
            response = await request(`${base}/ubcq?formula=rate-ready`);
        } catch {
            await delay(1000);
            continue;
        }
        assert(
            ![400, 429].includes(response.status),
            'Use a fresh rate-limit test deployment; check ingress trust',
        );
        if (response.status === 200) {
            ready = true;
            break;
        }
        await delay(1000);
    }
    assert(ready, 'API did not become ready');
    const formula = encodeURIComponent(`rate_${Date.now()}`);
    status(
        await request(`${base}/exp-svg?formula=${formula}`, {
            'X-Forwarded-For': '198.51.100.1',
            Forwarded: 'for=198.51.100.2',
            'X-Real-IP': '198.51.100.3',
        }),
        200,
    );
    limited(
        await request(`${base}/exp-svg?formula=${formula}%2B1`, {
            'X-Forwarded-For': '198.51.100.4',
        }),
    );
    const cached = await request(`${base}/exp-svg?formula=${formula}`, {
        'X-Forwarded-For': '198.51.100.5',
    });
    status(cached, 200);
    assert(cached.headers['content-security-policy']);
    for (const [formula, ip] of [
        ['x', 6],
        ['y', 7],
    ]) {
        status(
            await request(`${base}/ubcq?formula=${formula}`, {
                'X-Forwarded-For': `198.51.100.${ip}`,
            }),
            200,
        );
    }
    for (const [formula, ip] of [
        ['x', 8],
        ['z', 9],
    ]) {
        limited(
            await request(`${base}/ubcq?formula=${formula}`, {
                'X-Forwarded-For': `198.51.100.${ip}`,
            }),
        );
    }
    console.log(
        'PASS: spoof resistance, request/render quotas, Retry-After/no-store and cached SVG after quota exhaustion.',
    );
}
cli(
    main,
    { 'base-url': 'http://localhost:5017' },
    'test-rate-limits.sh [--base-url URL] (requires fresh rate-limit smoke deployment)',
);
