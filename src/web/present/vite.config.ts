// @lovable.dev/vite-tanstack-config already includes the following — do NOT add them manually
// or the app will break with duplicate plugins:
//   - TanStack devtools (dev-only, first), tanstackStart, viteReact, tailwindcss, tsConfigPaths,
//     nitro (build-only using cloudflare as a default target), VITE_* env injection, @ path alias,
//     React/TanStack dedupe, error logger plugins, and sandbox detection (port/host/strictPort).
// You can pass additional config via defineConfig({ vite: { ... }, etc... }) if needed.
import { defineConfig } from "@lovable.dev/vite-tanstack-config";

export default defineConfig({
  tanstackStart: {
    // Redirect TanStack Start's bundled server entry to src/server.ts (our SSR error wrapper).
    // nitro/vite builds from this
    server: { entry: "server" },
    // The deck is fully client-driven: checkpoints arrive over WebSocket and
    // every effect reads `window`. Nothing here needs a server at request
    // time, so the build prerenders one static shell and the Node backend can
    // serve it as plain files on the same origin as its API.
    spa: { enabled: true },
  },
  // No nitro/Cloudflare deploy bundle: `vite build` emits a static client dir
  // (dist/client) that src/web/backend serves directly at `/`.
  nitro: false,
});
