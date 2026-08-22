# statedb.app

Landing page and documentation for statedb. Astro + Starlight, fully static.

## Develop

```
./dev -- bash -lc 'cd site && bun install'
./dev -- bash -lc 'cd site && bun run dev'
./dev -- bash -lc 'cd site && bun run build'   # output in site/dist/
```

## Deploy (Cloudflare Pages)

- Root directory: `site`
- Build command: `bun run build`
- Build output directory: `dist`
- Custom domain: `statedb.app`

No server-side rendering, no adapter — plain static output.
