const { assert, root, alpine, docker, dockerAsync, project, cli } = require('./test-utils.cjs');
const { join } = require('node:path');
const { randomBytes } = require('node:crypto');
async function main(args) {
    const name = project(args['project-name']),
        network = `${name}_ingress-public`;
    docker(['network', 'inspect', network]);
    const names = [],
        jobs = [];
    try {
        for (const number of [1, 2]) {
            const container = `${name}-queue-probe-${randomBytes(5).toString('hex')}`;
            names.push(container);
            jobs.push(
                dockerAsync([
                    'run',
                    '--rm',
                    '--name',
                    container,
                    '--network',
                    network,
                    '--read-only',
                    '--cap-drop',
                    'ALL',
                    '--security-opt',
                    'no-new-privileges:true',
                    '--user',
                    '65534',
                    '--tmpfs',
                    '/tmp:rw,nosuid,nodev,noexec,size=20m,mode=1777',
                    '--mount',
                    `type=bind,source=${join(root, 'docker/ingress/render-queue-probe.sh')},target=/probe.sh,readonly`,
                    '-e',
                    `QUEUE_TEST_FORMULA=x%5E${number}%2B${Date.now()}`,
                    alpine,
                    'sh',
                    '/probe.sh',
                ]),
            );
        }
        for (const output of await Promise.all(jobs.map((job) => job.promise))) {
            assert.match(output, /PASS: concurrent unique render returned SVG/);
            console.log(output.trim());
        }
    } finally {
        for (const name of names) {
            docker(['rm', '-f', name], { allowFailure: true });
        }
        for (const job of jobs) {
            job.kill();
        }
        await Promise.allSettled(jobs.map((job) => job.promise));
    }
}
cli(
    main,
    { 'project-name': 'permaformula-queue-smoke' },
    'test-render-queue.sh [--project-name NAME] (requires isolated queue smoke deployment)',
);
