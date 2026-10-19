import { gzipSync } from 'node:zlib';
export function libraryPage(
    items: { id: string; kind: string; descriptor: { forumla: string }; createdAt: number }[],
) {
    return {
        items: items.map(({ descriptor, ...item }) => ({
            ...item,
            ubcq:
                item.kind === 'formula'
                    ? gzipSync(JSON.stringify(descriptor)).toString('base64')
                    : item.id,
            excerpt: descriptor.forumla.slice(0, 256),
        })),
        nextCursor: null,
    };
}
