// Side-effect CSS imports (e.g. `import "@/index.css"`) carry no types; the bun
// bundler handles them. Declare the module so `tsc --noEmit` accepts them.
declare module "*.css";
