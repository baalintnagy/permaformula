const {
    assert,
    root,
    alpine,
    docker,
    inspect,
    request,
    follow,
    svg,
    project,
    delay,
    Fixture,
    cli,
} = require('./test-utils.cjs');
const { join } = require('node:path');
async function main(args) {
    const name = project(args['project-name']),
        network = `${name}_api-private`;
    const privateNetwork = JSON.parse(docker(['network', 'inspect', network]).stdout)[0];
    assert(
        privateNetwork.Internal &&
            privateNetwork.Options['com.docker.network.bridge.gateway_mode_ipv4'] === 'isolated',
        'API network is not isolated',
    );
    const api = inspect(docker(['compose', '-p', name, 'ps', '-q', 'permaformula']).stdout);
    assert.deepEqual(
        Object.keys(api.NetworkSettings.Networks).sort(),
        [network, `${name}_cache-private`].sort(),
    );
    const cache = JSON.parse(docker(['network', 'inspect', `${name}_cache-private`]).stdout)[0];
    assert(
        cache.Internal &&
            cache.Options['com.docker.network.bridge.gateway_mode_ipv4'] === 'isolated',
        'Cache network is not isolated',
    );
    const probe = (...args) =>
        docker([
            'run',
            '--rm',
            '--network',
            network,
            '--cap-drop',
            'ALL',
            '--read-only',
            '--user',
            '65534',
            '--mount',
            `type=bind,source=${join(root, 'docker/egress/probe.sh')},target=/probe.sh,readonly`,
            alpine,
            'sh',
            '/probe.sh',
            ...args,
        ]).stdout;
    console.log(probe('direct'));
    for (const destination of [
        '127.0.0.1:443',
        '169.254.169.254:443',
        '10.0.0.1:443',
        'localhost:443',
        'permaformula:443',
        '[::1]:443',
        '[fc00::1]:443',
        '[64:ff9b::7f00:1]:443',
        '[2002:7f00:1::1]:443',
        '[2001:db8::1]:443',
        'cdnjs.cloudflare.com:80',
    ]) {
        assert.match(
            probe('tunnel', 'stylesheet-egress', destination),
            /^HTTP\/1.1 403 /,
            destination,
        );
    }
    assert.match(
        probe('tunnel', 'stylesheet-egress', 'cdnjs.cloudflare.com:443'),
        /^HTTP\/1.1 200 /,
    );
    const fixture = new Fixture(`${name}-dns`);
    try {
        docker([
            'build',
            '-q',
            '-f',
            'docker/egress/Dockerfile',
            '--target',
            'test-dns',
            '-t',
            'permaformula-egress-test-dns',
            '.',
        ]);
        const dns = fixture.container(`${fixture.name}-dns`, [
            '--network',
            network,
            '--cap-drop',
            'ALL',
            '--read-only',
            '--tmpfs',
            '/tmp',
            'permaformula-egress-test-dns',
        ]);
        const dnsIp = inspect(dns).NetworkSettings.Networks[network].IPAddress;
        const egress = inspect(
            docker(['compose', '-p', name, 'ps', '-q', 'stylesheet-egress']).stdout,
        );
        const proxy = fixture.container(`${fixture.name}-proxy`, [
            '--network',
            network,
            '--cap-drop',
            'ALL',
            '--read-only',
            '--tmpfs',
            '/tmp',
            '--memory',
            '128m',
            '--ulimit',
            'nofile=8192:8192',
            '--entrypoint',
            'sh',
            egress.Image,
            '-c',
            'cat /etc/squid/squid.conf > /tmp/test.conf; echo "dns_nameservers $1" >> /tmp/test.conf; exec squid -N -f /tmp/test.conf',
            'sh',
            dnsIp,
        ]);
        let listening = false;
        for (let attempt = 0; attempt < 20; attempt++) {
            if (
                docker(['exec', proxy, 'nc', '-z', '127.0.0.1', '3128'], { allowFailure: true })
                    .status === 0
            ) {
                listening = true;
                break;
            }
            await delay(250);
        }
        assert(listening, 'DNS proxy fixture did not start');
        for (const destination of ['mixed.test:443', 'private.test:443']) {
            assert.match(probe('tunnel', proxy, destination), /^HTTP\/1.1 403 /);
        }
        const base = args['base-url'];
        svg(await follow(`${base}/exp-svg?formula=x%5E2`));
        const query = new URLSearchParams({
            formula: '<p class="text-danger">Egress verified `x^2`</p>',
            imageWidth: '500',
            styleRefs: 'https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css',
        });
        const descriptor = await request(`${base}/ubcq?${query}`);
        assert.equal(descriptor.status, 200);
        const response = await follow(
            `${base}/html-svg?${new URLSearchParams({ ubcq: descriptor.text })}`,
        );
        svg(response);
        assert.match(response.text, /Egress verified/);
        assert.match(response.text, /rgb\(220,\s*53,\s*69\)/);
        console.log(
            'PASS: isolated API/cache networks, public-only HTTPS egress, mixed DNS rejection and Bootstrap-styled rendering.',
        );
    } finally {
        fixture.cleanup();
    }
}
cli(
    main,
    { 'project-name': 'permaformula-egress-smoke', 'base-url': 'http://localhost:5017' },
    'test-egress.sh [--project-name NAME] [--base-url URL] (requires isolated smoke deployment)',
);
