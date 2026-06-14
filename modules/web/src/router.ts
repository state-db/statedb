import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
} from "@tanstack/react-router";

import { AppShell } from "@/components/shell/AppShell";
import { HomePage } from "@/routes/home";
import { NotFoundPage } from "@/routes/not-found";

// Bare root renders an <Outlet />; the
// shell wraps everything that needs the header/nav frame.
const rootRoute = createRootRoute({ component: Outlet });

const notFoundRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "$",
  component: NotFoundPage,
});

const shellRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "shell",
  component: AppShell,
});

const indexRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "/",
  component: HomePage,
});

const routeTree = rootRoute.addChildren([
  shellRoute.addChildren([indexRoute]),
  notFoundRoute,
]);

export const router = createRouter({ routeTree });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
