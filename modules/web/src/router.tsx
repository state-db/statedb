import {
  createRootRoute,
  createRoute,
  Navigate,
  notFound,
  redirect,
  createRouter,
  Outlet,
} from "@tanstack/react-router";
import { Settings } from "lucide-react";

import { AppShell, useShell } from "@/components/shell/AppShell";
import { queryClient } from "@/lib/query-client";
import { HomePage } from "@/routes/home";
import { LoginPage } from "@/routes/login";
import { NotFoundPage } from "@/routes/not-found";
import { PlaceholderPage } from "@/routes/placeholder";
import { OrgStacksPage, ProjectStacksPage } from "@/routes/scoped-stacks";
import { StackDetailPage, type StackTab } from "@/routes/stack-detail";
import { RunDetailPage, prefetchRun } from "@/routes/run-detail";
import { AdminProvidersPage } from "@/routes/admin-providers";
import { AdminOrganizationsPage } from "@/routes/admin-orgs";
import { AdminUsersPage } from "@/routes/admin-users";
import { AdminOperationsPage } from "@/routes/admin-operations";
import { AdminEulaPage } from "@/routes/admin-eula";
import {
  AdminServiceTokensPage,
  canManageServiceTokens,
} from "@/routes/admin-service-tokens";
import {
  canManageOrganizationMembers,
  OrganizationMembersPage,
} from "@/routes/org-members";
import { AccountTokensPage } from "@/routes/account-tokens";

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

const orgRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "$org",
  component: OrgStacksRoute,
});

function OrgStacksRoute() {
  const { org } = orgRoute.useParams();
  return <OrgStacksPage org={org} />;
}

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
  beforeLoad: ({ params }) => {
    if (!/^\d+$/.test(params.version)) throw notFound();
  },
  loader: ({ params }) =>
    prefetchRun(queryClient, params.org, params.project, params.stack, {
      version: Number(params.version),
    }),
  component: UpdateDetailRoute,
});

function UpdateDetailRoute() {
  const { org, project, stack, version } = updateRoute.useParams();
  return <RunDetailPage org={org} project={project} stack={stack} locator={{ version: Number(version) }} />;
}

const previewRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "$org/$project/$stack/previews/$updateId",
  beforeLoad: ({ params }) => {
    if (!isUuid(params.updateId)) throw notFound();
  },
  loader: ({ params }) =>
    prefetchRun(queryClient, params.org, params.project, params.stack, {
      previewId: params.updateId,
    }),
  component: PreviewDetailRoute,
});

function PreviewDetailRoute() {
  const { org, project, stack, updateId } = previewRoute.useParams();
  return <RunDetailPage org={org} project={project} stack={stack} locator={{ previewId: updateId }} />;
}

const accountTokensRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "account/tokens",
  component: AccountTokensPage,
});

const adminRoute = createRoute({
  getParentRoute: () => shellRoute,
  id: "admin-only",
  component: AdminRoute,
});

function AdminRoute() {
  const { user } = useShell();
  return user.siteAdmin ? <Outlet /> : <Navigate to="/console" replace />;
}

const adminIndexRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: "admin",
  beforeLoad: () => {
    throw redirect({ to: "/console/admin/providers", replace: true });
  },
});

const adminProvidersRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: "admin/providers",
  component: AdminProvidersPage,
});

const adminOrgsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: "admin/orgs",
  component: AdminOrganizationsPage,
});

const adminOrgMembersRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin/orgs/$org",
  component: AdminOrgMembersRoute,
});

function AdminOrgMembersRoute() {
  const { org } = adminOrgMembersRoute.useParams();
  const { user } = useShell();
  return canManageOrganizationMembers(user, org) ? (
    <OrganizationMembersPage org={org} />
  ) : (
    <Navigate to="/console/$org" params={{ org }} replace />
  );
}

const adminUsersRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: "admin/users",
  component: AdminUsersPage,
});

const adminTokensRoute = createRoute({
  getParentRoute: () => shellRoute,
  path: "admin/tokens",
  component: AdminTokensRoute,
});

function AdminTokensRoute() {
  const { user } = useShell();
  return canManageServiceTokens(user) ? <AdminServiceTokensPage /> : <Navigate to="/console" replace />;
}

const adminOperationsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: "admin/operations",
  component: AdminOperationsPage,
});

const adminEulaRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: "admin/eula",
  component: AdminEulaPage,
});

const adminLicenseRoute = createRoute({
  getParentRoute: () => adminRoute,
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
    orgRoute,
    projectRoute,
    stackRoute,
    updateRoute,
    previewRoute,
    accountTokensRoute,
    adminOrgMembersRoute,
    adminTokensRoute,
    adminRoute.addChildren([
      adminIndexRoute,
      adminProvidersRoute,
      adminOrgsRoute,
      adminUsersRoute,
      adminOperationsRoute,
      adminEulaRoute,
      adminLicenseRoute,
    ]),
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
