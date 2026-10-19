const { assert, root, docker, inspect, delay, Fixture, cli } = require('./test-utils.cjs');
const net = require('node:net');
const { join } = require('node:path');
async function connect(port, sockets) {
    const socket = net.connect({ host: '127.0.0.1', port, highWaterMark: 1024 });
    sockets.push(socket);
    socket.pause();
    await new Promise((resolve, reject) => {
        socket.once('connect', resolve);
        socket.once('error', reject);
    });
    return socket;
}
function headers(socket) {
    if (socket.destroyed || socket.readableEnded) {
        return Promise.resolve('');
    }
    return new Promise((resolve, reject) => {
        let text = '';
        const finish = (error) => {
            clearTimeout(timer);
            socket.pause();
            socket.off('data', data);
            socket.off('end', end);
            socket.off('error', failed);
            error ? reject(error) : resolve(text.split('\r\n\r\n')[0]);
        };
        const data = (part) => {
            text += part.toString('ascii');
            if (text.includes('\r\n\r\n')) {
                finish();
            } else if (text.length > 32768) {
                finish(new Error('Fixture response headers too large'));
            }
        };
        const end = () => finish();
        const failed = (error) => finish(error.code === 'ECONNRESET' ? undefined : error);
        const timer = setTimeout(
            () => finish(new Error('Timed out reading fixture headers')),
            10000,
        );
        socket.on('data', data);
        socket.once('end', end);
        socket.once('error', failed);
        socket.resume();
    });
}
async function main(args) {
    const image = args.image,
        fixture = new Fixture('permaformula-ingress-limits'),
        sockets = [];
    try {
        const network = fixture.network();
        const common = [
            '--network',
            network,
            '--read-only',
            '--cap-drop',
            'ALL',
            '--security-opt',
            'no-new-privileges:true',
            '--tmpfs',
            '/tmp:rw,nosuid,nodev,noexec,size=32m,mode=1777',
        ];
        const upstream = fixture.container(`${fixture.name}-upstream`, [
            ...common,
            '--network-alias',
            'permaformula',
            '--mount',
            `type=bind,source=${join(root, 'docker/ingress/test-limits-upstream.conf')},target=/fixture.conf,readonly`,
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
        docker(['exec', upstream, 'truncate', '-s', '16777216', '/tmp/large.bin']);
        const ingress = fixture.container(`${fixture.name}-proxy`, [
            ...common,
            '-p',
            '127.0.0.1::8080',
            '-p',
            '127.0.0.1::8081',
            '-e',
            'PERMAFORMULA_DEPLOYMENT_PROFILE=separate-origins',
            '-e',
            'PERMAFORMULA_HOST=http://api.example.test',
            '-e',
            'PERMAFORMULA_IMAGE_HOST=http://images.example.test',
            ...Object.entries({
                CLIENT_CONNECTIONS: 2,
                TOTAL_CONNECTIONS: 3,
                HEADER_TIMEOUT_SECONDS: 2,
                BODY_TIMEOUT_SECONDS: 3,
                SEND_TIMEOUT_SECONDS: 2,
                KEEPALIVE_TIMEOUT_SECONDS: 2,
            }).flatMap(([key, value]) => ['-e', `PERMAFORMULA_INGRESS_${key}=${value}`]),
            image,
        ]);
        const ports = inspect(ingress).NetworkSettings.Ports;
        const api = +ports['8080/tcp'][0].HostPort,
            images = +ports['8081/tcp'][0].HostPort;
        docker(['exec', ingress, 'nginx', '-t', '-c', '/tmp/nginx.conf', '-e', 'stderr']);
        docker([
            'exec',
            ingress,
            'sh',
            '-c',
            "sed -i 's@error_log /dev/stderr warn;@error_log /dev/stderr info;@' /tmp/nginx.conf; nginx -s reload -c /tmp/nginx.conf -e stderr",
        ]);
        await delay(250);
        const large = await connect(api, sockets);
        large.write(
            `GET /ubcq?formula=${'x'.repeat(17 * 1024 * 1024)} HTTP/1.1\r\nHost: api.example.test\r\nConnection: close\r\n\r\n`,
        );
        assert.match(await headers(large), /^HTTP\/1.1 200/, '17 MiB request-line allowance lost');
        large.destroy();
        const oversized = await connect(api, sockets);
        oversized.write(
            'GET /ubcq HTTP/1.1\r\nHost: api.example.test\r\nContent-Length: 8388609\r\nConnection: close\r\n\r\n',
        );
        assert.match(await headers(oversized), /^HTTP\/1.1 413/);
        oversized.destroy();
        const first = await connect(api, sockets),
            second = await connect(images, sockets),
            started = Date.now();
        first.write(
            'GET /ubcq HTTP/1.1\r\nHost: api.example.test\r\nContent-Length: 1000\r\n\r\nx',
        );
        second.write(
            'GET /exp-svg HTTP/1.1\r\nHost: images.example.test\r\nContent-Length: 1000\r\n\r\nx',
        );
        await delay(200);
        const third = await connect(images, sockets);
        third.write(
            'GET /exp-svg HTTP/1.1\r\nHost: images.example.test\r\nX-Forwarded-For: 8.8.8.8\r\nConnection: close\r\n\r\n',
        );
        const rejected = await headers(third);
        assert.match(rejected, /^HTTP\/1.1 429/);
        assert.match(rejected, /^Retry-After: 1\r?$/im);
        assert.match(rejected, /^Access-Control-Allow-Origin: http:\/\/api.example.test\r?$/im);
        assert.match(await headers(first), /^(HTTP\/1.1 408|$)/);
        assert.match(await headers(second), /^(HTTP\/1.1 408|$)/);
        assert(
            Date.now() - started >= 2000 && Date.now() - started <= 8000,
            'Body idle budget not enforced',
        );
        const partial = await connect(api, sockets);
        partial.write('GET /ubcq HTTP/1.1\r\nHost: api.example.test\r\nX-Slow: ');
        assert.match(await headers(partial), /^(HTTP\/1.1 408|$)/);
        let logs = docker(['logs', ingress]);
        assert.match(
            logs.stdout + logs.stderr,
            /client timed out .*while reading client request headers/,
        );
        const alive = await connect(api, sockets);
        alive.write('GET /ubcq HTTP/1.1\r\nHost: api.example.test\r\n\r\n');
        assert.match(await headers(alive), /^HTTP\/1.1 200/);
        assert.equal(await headers(alive), '', 'Idle keepalive did not close');
        const slow = await connect(api, sockets);
        slow.write('GET /ubcq?large=1 HTTP/1.1\r\nHost: api.example.test\r\n\r\n');
        assert.match(await headers(slow), /^HTTP\/1.1 200/);
        await delay(5000);
        logs = docker(['logs', ingress]);
        assert.match(logs.stdout + logs.stderr, /client timed out .*while sending to client/);
        for (const socket of sockets) {
            socket.destroy();
        }
        docker([
            'exec',
            ingress,
            'sh',
            '-c',
            "sed -i 's/limit_conn clients 2;/limit_conn clients 4;/; s/limit_conn total 3;/limit_conn total 1;/' /tmp/nginx.conf; nginx -s reload -c /tmp/nginx.conf -e stderr",
        ]);
        await delay(300);
        const held = await connect(api, sockets);
        held.write('GET /ubcq HTTP/1.1\r\nHost: api.example.test\r\nContent-Length: 1000\r\n\r\nx');
        await delay(200);
        const total = await connect(images, sockets);
        total.write(
            'GET /exp-svg HTTP/1.1\r\nHost: images.example.test\r\nConnection: close\r\n\r\n',
        );
        assert.match(await headers(total), /^HTTP\/1.1 429/);
        logs = docker(['logs', ingress]);
        assert.match(logs.stdout + logs.stderr, /limiting connections by zone "total"/);
        const invalid = docker(
            [
                'run',
                '--rm',
                '-e',
                'PERMAFORMULA_HOST=http://api.example.test',
                '-e',
                'PERMAFORMULA_IMAGE_HOST=http://images.example.test',
                '-e',
                'PERMAFORMULA_INGRESS_CLIENT_CONNECTIONS=2;bad',
                image,
            ],
            { allowFailure: true },
        );
        assert.notEqual(invalid.status, 0);
        assert.match(invalid.stdout + invalid.stderr, /positive decimal integers/);
        console.log(
            'PASS: large request line, body cap, shared client/total caps, spoof resistance, Retry-After/CORS, header/body/keepalive/write timeouts and recovery.',
        );
    } finally {
        for (const socket of sockets) {
            socket.destroy();
        }
        fixture.cleanup();
    }
}
cli(main, { image: 'permaformula-ingress:latest' }, 'test-ingress-limits.sh [--image IMAGE]');
