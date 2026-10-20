const {
    assert,
    root,
    docker,
    inspect,
    request,
    status,
    svg,
    jpeg,
    project,
    cli,
} = require('./test-utils.cjs');
const { checkIngress, credentials } = require('./test-production-ingress.cjs');
const { join } = require('node:path');
async function main(args) {
    const name = project(args['project-name']),
        api = args['base-url'],
        images = args['image-url'];
    const descriptor = await request(
        `${api}/ubcq?${new URLSearchParams({ formula: '<p>Isolated image `x^2`</p>' })}`,
    );
    status(descriptor, 200);
    assert.match(descriptor.text, /^H4sI[A-Za-z0-9+/]*={0,2}$/);
    const htmlQuery = new URLSearchParams({ ubcq: descriptor.text }).toString();
    svg(await request(`${images}/html-svg?${htmlQuery}`, credentials));
    const formulaPath = '/exp-svg?formula=x%5E2';
    const redirect = await request(api + formulaPath, credentials);
    status(redirect, 307);
    assert.equal(redirect.headers.location, images + formulaPath);
    svg(await request(images + formulaPath, credentials));
    for (const path of ['/exp-jpg?formula=x%5E2', `/html-jpg?${htmlQuery}`]) {
        const redirected = await request(api + path, credentials);
        status(redirected, 307);
        assert.equal(redirected.headers.location, images + path);
        jpeg(await request(images + path, credentials));
        status(await request(images + path, {}, 'POST'), 403);
        status(await request(images + path, { Origin: api }, 'OPTIONS'), 204);
    }
    for (const path of [
        '/ubcq?formula=x',
        '/url-encode?text=x',
        '/decode?ubcq=bad',
        '/swagger-ui/index.html',
        '/v3/api-docs',
        '/exp-svg;ignored?formula=x',
        '/',
        '/mathjax/startup.js',
    ]) {
        status(await request(images + path), 404);
    }
    status(await request(`${api}/exp-svg;ignored?formula=x`, credentials), 404);
    status(await request(`${images}/exp-svg?formula=x`, {}, 'POST'), 403);
    const cors = await request(`${images}/exp-svg?formula=x`, { Origin: api }, 'OPTIONS');
    status(cors, 204);
    assert.equal(cors.headers['access-control-allow-origin'], api);
    assert.equal(cors.headers['access-control-allow-credentials'], undefined);
    status(await request(`${api}/swagger-ui/index.html`), 200);
    const builder = await request(`${api}/`);
    status(builder, 200);
    assert.match(builder.text, /PermaFormula/);
    assert.match(builder.headers['content-type'], /^text\/html/);
    status(await request(`${api}/mathjax/startup.js`), 200);
    for (const path of ['/decode?ubcq=bad', '/url?formula=x', '/url-encode?text=x']) {
        status(await request(api + path), 404);
    }
    const image = inspect(`${name}-ingress-1`).Config.Image;
    for (const path of [
        '/exp-svg?formula=x',
        '/exp-svg;ignored?formula=x',
        '/exp-jpg?formula=x',
        '/html-jpg?ubcq=bad',
    ]) {
        const direct = docker([
            'run',
            '--rm',
            '--network',
            `${name}_api-private`,
            '--read-only',
            '--cap-drop',
            'ALL',
            '--security-opt',
            'no-new-privileges:true',
            '--mount',
            `type=bind,source=${join(root, 'docker/ingress/delivery-probe.sh')},target=/probe.sh,readonly`,
            '--entrypoint',
            'sh',
            image,
            '/probe.sh',
            path,
        ]).stdout;
        assert.match(
            direct,
            /^HTTP\/1.1 307 /,
            'Private API accepted an untrusted image-delivery marker',
        );
    }
    await checkIngress(image, 'separate-origins');
    await checkIngress(image, 'single-host');
    console.log(
        'PASS: SVG/JPEG headers, anonymous CORS, redirects/queries, route restrictions, direct API trust and ingress credential boundaries.',
    );
}
cli(
    main,
    {
        'project-name': 'permaformula-delivery-smoke',
        'base-url': 'http://localhost:5017',
        'image-url': 'http://localhost:5018',
    },
    'test-image-delivery.sh [--project-name NAME] [--base-url URL] [--image-url URL] (requires separate-origins smoke deployment)',
);
