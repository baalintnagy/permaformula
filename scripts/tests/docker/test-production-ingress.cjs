const {
    assert,
    root,
    docker,
    inspect,
    request,
    status,
    ready,
    Fixture,
    cli,
} = require('./test-utils.cjs');
const { join } = require('node:path');
const credentials = {
    Cookie: 'app_session=sentinel',
    Authorization: 'Bearer sentinel',
    'Proxy-Authorization': 'Basic sentinel',
    'X-PermaFormula-Image-Delivery': 'forged',
    'X-Forwarded-For': '198.51.100.123',
    'X-Real-IP': '203.0.113.12',
    'X-Forwarded-Proto': 'https',
};
async function checkIngress(image, profile, trustHostProxy = false) {
    assert(['single-host', 'separate-origins'].includes(profile), 'Invalid profile');
    const fixture = new Fixture(`permaformula-ingress-${profile}`);
    try {
        const network = fixture.network();
        const gateway = JSON.parse(docker(['network', 'inspect', network]).stdout)[0].IPAM.Config[0]
            .Gateway;
        const common = [
            '--network',
            network,
            '--read-only',
            '--cap-drop',
            'ALL',
            '--security-opt',
            'no-new-privileges:true',
            '--tmpfs',
            '/tmp:rw,nosuid,nodev,noexec,size=16m,mode=1777',
        ];
        fixture.container(`${fixture.name}-upstream`, [
            ...common,
            '--network-alias',
            'permaformula',
            '--mount',
            `type=bind,source=${join(root, 'docker/ingress/test-upstream.conf')},target=/fixture.conf,readonly`,
            '--entrypoint',
            'nginx',
            image,
            '-c',
            '/fixture.conf',
            '-e',
            'stderr',
            '-g',
            'daemon off;',
        ]);
        const ingress = fixture.container(`${fixture.name}-proxy`, [
            ...common,
            '-p',
            '127.0.0.1::8080',
            '-p',
            '127.0.0.1::8081',
            '-e',
            `PERMAFORMULA_DEPLOYMENT_PROFILE=${profile}`,
            '-e',
            'PERMAFORMULA_HOST=http://api.example.test',
            '-e',
            'PERMAFORMULA_IMAGE_HOST=http://images.example.test',
            ...(trustHostProxy ? ['-e', `PERMAFORMULA_INGRESS_TRUSTED_PROXY=${gateway}`] : []),
            image,
        ]);
        const ports = inspect(ingress).NetworkSettings.Ports;
        const api = `http://127.0.0.1:${ports['8080/tcp'][0].HostPort}`;
        const images =
            profile === 'single-host' ? api : `http://127.0.0.1:${ports['8081/tcp'][0].HostPort}`;
        await ready(`${api}/ubcq`);
        const control = await request(`${api}/ubcq`, credentials);
        status(control, 200);
        assert.equal(
            control.headers['x-fixture-forwarded-for'],
            trustHostProxy ? '203.0.113.12' : gateway,
        );
        assert.equal(
            control.headers['x-fixture-forwarded-proto'],
            trustHostProxy ? 'https' : 'http',
        );
        assert.match(control.text, /^app_session=sentinel\|Bearer sentinel\|.*\|$/);
        assert(control.headers['set-cookie'], 'Fixture must set its control cookie');
        for (const path of ['/exp-svg', '/html-svg', '/exp-jpg', '/html-jpg']) {
            status(await request(`${images}${path}`, {}, 'POST'), 403);
            status(await request(`${images}${path}`, {}, 'OPTIONS'), 204);
            const response = await request(`${images}${path}`, credentials);
            status(response, 200);
            assert.equal(
                response.text,
                '|||1',
                'Image credentials or forged delivery marker leaked',
            );
            assert.equal(response.headers['set-cookie'], undefined);
            assert.match(response.headers['content-security-policy'] || '', /sandbox/);
            status(await request(`${images}${path};ignored?formula=x`, credentials), 404);
        }
        for (const path of ['/exp', '/html', '/library-svg']) {
            status(await request(`${api}${path}`, credentials), 404);
            status(await request(`${images}${path}`, credentials), 404);
        }
        if (profile === 'separate-origins') {
            status(await request(`${api}/exp-svg?formula=x`, credentials), 307);
            status(await request(`${images}/ubcq`), 404);
        }
        console.log(
            `PASS: ${profile}, trusted proxy=${trustHostProxy}: credentials, markers, cookies, routes, methods and forwarded identity.`,
        );
    } finally {
        fixture.cleanup();
    }
}
if (require.main === module) {
    cli(
        (args) => checkIngress(args.image, args.profile, args['trust-host-proxy']),
        { image: 'permaformula-ingress-security', profile: 'single-host', 'trust-host-proxy': false },
        'test-production-ingress.sh [--image IMAGE] [--profile single-host|separate-origins] [--trust-host-proxy]',
        ['trust-host-proxy'],
    );
}
module.exports = { checkIngress, credentials };
