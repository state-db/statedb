# statedb.app

Landing page and documentation for statedb. Astro + Starlight, fully static.

## Develop

```
./bin/in-container bash -lc 'cd site && bun install'
./bin/in-container bash -lc 'cd site && bun run dev'
./bin/in-container bash -lc 'cd site && bun run build'   # output in site/dist/
```

## Deploy (Cloudflare Pages)

- Root directory: `site`
- Build command: `bun run build`
- Build output directory: `dist`
- Custom domain: `statedb.app`

No server-side rendering, no adapter — plain static output.
