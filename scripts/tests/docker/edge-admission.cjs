// Local-only memory/admission probes shared by the ACME fixture.
const tls = require('node:tls');
const { once } = require('node:events');
const { assert, docker, delay, inspect, root } = require('./test-utils.cjs');
const { join } = require('node:path');

function memory(container) {
    const values = docker([
        'exec',
        container,
        'sh',
        '-c',
        'cat /sys/fs/cgroup/memory.current; cat /sys/fs/cgroup/memory.peak; cat /sys/fs/cgroup/memory.events',
    ]).stdout;
    const [current, peak] = values.split('\n').map(Number);
    return {
        currentMiB: +(current / 1048576).toFixed(1),
        peakMiB: +(peak / 1048576).toFixed(1),
        oomKills: Number(values.match(/^oom_kill (\d+)$/m)[1]),
    };
}
async function open(port, ca) {
    const socket = tls.connect({
        host: '127.0.0.1',
        port,
        servername: 'nginx.test',
        ca,
        ALPNProtocols: ['http/1.1'],
    });
    socket.on('error', () => {});
    await Promise.race([
        once(socket, 'secureConnect'),
        delay(3000).then(() => {
            throw new Error('TLS admission deadline');
        }),
    ]);
    socket.on('data', () => {});
    return socket;
}
async function measure({ container, port, ca, candidate, get }) {
    const sockets = [],
        payload = Buffer.from('GET /ubcq?formula=' + 'x'.repeat(17 * 1048576));
    const rows = [];
    try {
        rows.push({ clients: 0, ...memory(container) });
        for (let target = 4; target <= (candidate ? 12 : 32); target += 4) {
            let accepted = 0;
            for (let i = target - 4; i < target; i++) {
                try {
                    const socket = await open(port, ca);
                    sockets.push(socket);
                    accepted++;
                    socket.write(payload, () => {});
                } catch {
                    /* Rejected transport or worker exhaustion is recorded below. */
                }
            }
            await delay(600);
            const row = {
                clients: target,
                newlyAccepted: accepted,
                open: sockets.filter((s) => !s.destroyed).length,
                ...memory(container),
            };
            rows.push(row);
            console.log('MEMORY ' + JSON.stringify(row));
            if (row.oomKills || row.currentMiB > 460) {
                break;
            }
        }
        if (candidate) {
            assert.equal(rows.at(-1).oomKills, 0);
            assert(rows.at(-1).currentMiB < 400, 'Insufficient memory headroom');
            assert(rows.at(-1).open <= 10, 'Per-client admission did not hold');
        }
    } finally {
        for (const socket of sockets) {
            socket.destroy();
        }
        await delay(1000);
    }
    assert.equal((await get('/ubcq')).status, 200, 'Ordinary traffic failed to recover');
    console.log(
        'MEMORY_RESULT ' +
            JSON.stringify({
                candidate,
                rows,
                recovered: memory(container),
                containerOOM: inspect(container).State.OOMKilled,
            }),
    );
}
module.exports = { memory, open, measure };

async function distributed({ fixture, container, temporary, get }) {
    const image =
        'node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f';
    function client(name, mode, count) {
        return fixture.container(fixture.name + '-' + name, [
            '--network',
            fixture.name,
            '--read-only',
            '--cap-drop',
            'ALL',
            '--security-opt',
            'no-new-privileges:true',
            '--memory',
            '512m',
            '--pids-limit',
            '32',
            '--mount',
            `type=bind,source=${temporary},target=/fixture,readonly`,
            '--mount',
            `type=bind,source=${join(root, 'scripts/tests/docker/admission-client.cjs')},target=/client.cjs,readonly`,
            image,
            'node',
            '/client.cjs',
            mode,
            String(count),
        ]);
    }
    function state(name) {
        const lines = docker(['logs', name]).stdout.split('\n').filter(Boolean);
        return JSON.parse(lines.at(-1));
    }
    const clients = [];
    try {
        clients.push(client('first', 'raw', 10));
        await delay(1300);
        assert.equal(state(clients[0]).open, 10);
        assert.equal(
            (await get('/ubcq')).status,
            200,
            'One saturated client blocked another address',
        );
        clients.push(client('second', 'raw', 2));
        await delay(1300);
        clients.push(client('excess', 'raw', 1));
        await delay(1000);
        assert.equal(state(clients[0]).open + state(clients[1]).open, 12);
        assert.equal(state(clients[2]).open, 0, 'Global TCP cap bypassed');
        const sample = memory(container);
        assert.equal(sample.oomKills, 0);
        assert(sample.currentMiB < 400);
        console.log('GLOBAL_RAW ' + JSON.stringify(sample));
    } finally {
        for (const name of clients) {
            docker(['rm', '-f', name], { allowFailure: true });
        }
    }
    await delay(500);
    assert.equal((await get('/ubcq')).status, 200);
    const complete = [];
    try {
        complete.push(client('h1-first', 'complete', 10));
        await delay(1500);
        complete.push(client('h1-second', 'complete', 2));
        await delay(2000);
        const sample = memory(container);
        console.log(
            'GLOBAL_COMPLETE_H1 ' + JSON.stringify({ ...sample, clients: complete.map(state) }),
        );
        assert.equal(
            complete.map(state).reduce((sum, s) => sum + s.open, 0),
            12,
        );
        assert.equal(
            complete.map(state).reduce((sum, s) => sum + s.responses, 0),
            12,
        );
        assert.equal(sample.oomKills, 0);
        assert(sample.peakMiB < 820, 'Complete HTTP/1.1 URLs lack memory headroom');
    } finally {
        for (const name of complete) {
            docker(['rm', '-f', name], { allowFailure: true });
        }
    }
    await delay(600);
    const redirects = [];
    try {
        redirects.push(client('redirect-first', 'redirect', 10));
        await delay(1500);
        redirects.push(client('redirect-second', 'redirect', 2));
        await delay(2000);
        const sample = memory(container);
        console.log(
            'GLOBAL_REDIRECT ' + JSON.stringify({ ...sample, clients: redirects.map(state) }),
        );
        assert.equal(
            redirects.map(state).reduce((sum, s) => sum + s.responses, 0),
            12,
        );
        assert.equal(sample.oomKills, 0);
        assert(sample.peakMiB < 820, 'Large HTTP redirects lack memory headroom');
    } finally {
        for (const name of redirects) {
            docker(['rm', '-f', name], { allowFailure: true });
        }
    }
    await delay(600);
    assert.equal((await get('/ubcq')).status, 200);
    const slow = await open(
        inspect(container).NetworkSettings.Ports['8443/tcp'][0].HostPort,
        require('node:fs').readFileSync(join(temporary, 'issued-root.pem')),
    );
    let timer;
    try {
        const start = Date.now();
        slow.write('GET /ubcq?formula=' + 'x'.repeat(4096));
        timer = setInterval(() => {
            if (!slow.destroyed) {
                slow.write('x');
            }
        }, 300);
        await Promise.race([
            once(slow, 'close'),
            delay(18000).then(() => {
                throw new Error('Slow URL exceeded header deadline');
            }),
        ]);
        console.log(
            'PASS: trickled unfinished URL closed after ' +
                (Date.now() - start) +
                ' ms; ongoing bytes did not reset the deadline.',
        );
    } finally {
        clearInterval(timer);
        slow.destroy();
    }
    assert.equal((await get('/ubcq')).status, 200);
}
module.exports.distributed = distributed;
