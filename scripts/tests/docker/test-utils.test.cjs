const { test } = require('node:test');
const http = require('node:http');
const { assert, options, request, follow, status, svg, jpeg } = require('./test-utils.cjs');
test('CLI rejects unknown options and missing values before running tests', () => {
    assert.deepEqual(
        options(
            ['--profile', 'separate-origins', '--trust-host-proxy'],
            { profile: 'single-host', 'trust-host-proxy': false },
            ['trust-host-proxy'],
        ),
        { profile: 'separate-origins', 'trust-host-proxy': true },
    );
    assert.throws(() => options(['--unknown'], {}), /Unknown option/);
    assert.throws(() => options(['--image'], { image: 'default' }), /Missing value/);
    assert.deepEqual(options(['--help'], {}), { help: true });
});
test('HTTP checks preserve redirects, cookies, credentials, binary output and image policy', async () => {
    const server = http.createServer((req, res) => {
        if (req.url === '/redirect') {
            res.writeHead(307, { Location: '/svg', 'Set-Cookie': 'fixture=1' });
            res.end();
        } else if (req.url === '/headers') {
            res.end(`${req.headers.cookie}|${req.headers.authorization}`);
        } else {
            const isJpeg = req.url === '/jpeg';
            res.writeHead(200, {
                'Content-Type': isJpeg ? 'image/jpeg' : 'image/svg+xml',
                'Content-Security-Policy': "sandbox; default-src 'none'",
                'X-Content-Type-Options': 'nosniff',
                'Referrer-Policy': 'no-referrer',
            });
            res.end(isJpeg ? Buffer.from([255, 216, 255, 217]) : '<svg/>');
        }
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    try {
        const base = `http://127.0.0.1:${server.address().port}`;
        const redirect = await request(`${base}/redirect`);
        status(redirect, 307);
        assert.equal(redirect.headers.location, '/svg');
        assert(redirect.headers['set-cookie']);
        svg(await follow(`${base}/redirect`));
        assert.equal(
            (
                await request(`${base}/headers`, {
                    Cookie: 'private=1',
                    Authorization: 'Bearer fixture',
                })
            ).text,
            'private=1|Bearer fixture',
        );
        assert.equal(
            (await request(`${base}/headers`)).text,
            'undefined|undefined',
            'No cookie jar may persist between requests',
        );
        svg(await request(`${base}/svg`));
        jpeg(await request(`${base}/jpeg`));
        const invalid = await request(`${base}/svg`);
        invalid.headers['set-cookie'] = ['bad=1'];
        assert.throws(() => svg(invalid));
    } finally {
        await new Promise((resolve) => server.close(resolve));
    }
});
