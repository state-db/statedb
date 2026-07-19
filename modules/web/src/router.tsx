import {
  createRootRoute,
  createRoute,
  notFound,
  redirect,
  createRouter,
  Outlet,
} from "@tanstack/react-router";
import {
  Building2,
  KeyRound,
  ServerCog,
  Settings,
  Shield,
  Users,
} from "lucide-react";

import { AppShell } from "@/components/shell/AppShell";
import { HomePage } from "@/routes/home";
import { LoginPage } from "@/routes/login";
import { NotFoundPage } from "@/routes/not-found";
import { PlaceholderPage } from "@/routes/placeholder";
import { ProjectStacksPage } from "@/routes/scoped-stacks";
import { StackDetailPage, type StackTab } from "@/routes/stack-detail";

// Bare root renders an <Outlet />; the
// shell wraps everything that needs the header/nav frame.
const rootRoute = createRootRoute({
  component: Outlet,
  notFoundComponent: NotFoundPage,
});

const rootIndexRedirectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  beforeLoad: () => {
    throw redirect({ to: "/console", replace: true });
  },
});

const accountTokensRedirectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/account/tokens",
  beforeLoad: () => {
    throw redirect({ to: "/console/account/tokens", replace: true });
  },
});

// Newer Pulumi CLIs print /user/settings/tokens as the access-token page hint
// (older ones print /account/tokens). Both land on the same console screen.
const userSettingsTokensRedirectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/user/settings/tokens",
  beforeLoad: () => {
    throw redirect({ to: "/console/account/tokens", replace: true });
  },
});

const stackRedirectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/$org/$project/$stack",
  beforeLoad: ({ params }) => {
    throw redirect({
      to: "/console/$org/$project/$stack",
      params,
      replace: true,
    });
  },
});

const updateRedirectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/$org/$project/$stack/updates/$version",
  beforeLoad: ({ params }) => {
    if (!/^\d+$/.test(params.version)) {
      throw notFound();
    }
    throw redirect({
      to: "/console/$org/$project/$stack/updates/$version",
      params,
      replace: true,
    });
  },
});

const previewRedirectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/$org/$project/$stack/previews/$updateId",
  beforeLoad: ({ params }) => {
    if (!isUuid(params.updateId)) {
      throw notFound();
    }
    throw redirect({
      to: "/console/$org/$project/$stack/previews/$updateId",
      params,
      replace: true,
    });
  },
});

const notFoundRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "$",
  component: NotFoundPage,
});

const shellRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/console",
  component: AppShell,
});

const indexRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "/",
  component: HomePage,
});

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/console/login",
  component: LoginPage,
});

const projectRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "$org/$project",
  component: ProjectStacksRoute,
});

function ProjectStacksRoute() {
  const { org, project } = projectRoute.useParams();
  return <ProjectStacksPage org={org} project={project} />;
}

const stackRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "$org/$project/$stack",
  validateSearch: (search: Record<string, unknown>): { tab?: StackTab } => ({
    tab:
      search.tab === "outputs" || search.tab === "activity" || search.tab === "settings"
        ? search.tab
        : undefined,
  }),
  component: StackDetailRoute,
});

function StackDetailRoute() {
  const { org, project, stack } = stackRoute.useParams();
  const { tab } = stackRoute.useSearch();
  const navigate = stackRoute.useNavigate();
  return (
    <StackDetailPage
      org={org}
      project={project}
      stack={stack}
      tab={tab ?? "resources"}
      onTabChange={(nextTab) =>
        void navigate({
          search: nextTab === "resources" ? {} : { tab: nextTab },
          replace: true,
        })
      }
    />
  );
}

const updateRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "$org/$project/$stack/updates/$version",
  component: () => (
    <PlaceholderPage
      icon={ServerCog}
      title="Update detail"
      description="Run history and event logs fill in here in S8."
    />
  ),
});

const previewRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "$org/$project/$stack/previews/$updateId",
  component: () => (
    <PlaceholderPage
      icon={ServerCog}
      title="Preview detail"
      description="Preview history and event logs fill in here in S8."
    />
  ),
});

const accountTokensRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "account/tokens",
  component: () => (
    <PlaceholderPage
      icon={KeyRound}
      title="Personal access tokens"
      description="CLI token minting and personal tokens fill in here in S9."
    />
  ),
});

const adminIndexRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin",
  beforeLoad: () => {
    throw redirect({ to: "/console/admin/providers", replace: true });
  },
});

const adminProvidersRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin/providers",
  component: () => (
    <PlaceholderPage
      icon={Shield}
      title="OIDC providers"
      description="Provider administration fills in here in S10."
    />
  ),
});

const adminOrgsRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin/orgs",
  component: () => (
    <PlaceholderPage
      icon={Building2}
      title="Organizations and members"
      description="Organization administration fills in here in S11."
    />
  ),
});

const adminUsersRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin/users",
  component: () => (
    <PlaceholderPage
      icon={Users}
      title="Users"
      description="User administration fills in here in S12."
    />
  ),
});

const adminTokensRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin/tokens",
  component: () => (
    <PlaceholderPage
      icon={KeyRound}
      title="Service-account tokens"
      description="Service token administration fills in here in S13."
    />
  ),
});

const adminOperationsRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin/operations",
  component: () => (
    <PlaceholderPage
      icon={ServerCog}
      title="Operations"
      description="Locks, compaction, and retention health fill in here in S14."
    />
  ),
});

const adminLicenseRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin/license",
  component: () => (
    <PlaceholderPage
      icon={Settings}
      title="License and usage"
      description="Free Edition limits and usage fill in here in S15."
    />
  ),
});

const routeTree = rootRoute.addChildren([
  rootIndexRedirectRoute,
  accountTokensRedirectRoute,
  userSettingsTokensRedirectRoute,
  stackRedirectRoute,
  updateRedirectRoute,
  previewRedirectRoute,
  loginRoute,
  shellRoute.addChildren([
    indexRoute,
    projectRoute,
    stackRoute,
    updateRoute,
    previewRoute,
    accountTokensRoute,
    adminIndexRoute,
    adminProvidersRoute,
    adminOrgsRoute,
    adminUsersRoute,
    adminTokensRoute,
    adminOperationsRoute,
    adminLicenseRoute,
  ]),
  notFoundRoute,
]);

export const router = createRouter({ routeTree });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

function isUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    value,
  );
}
