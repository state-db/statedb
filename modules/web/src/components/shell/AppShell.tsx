import { Link, Outlet, useLocation, useNavigate } from "@tanstack/react-router";
import {
  Building2,
  ChevronDown,
  Database,
  KeyRound,
  LogOut,
  Moon,
  ServerCog,
  Shield,
  Sun,
  UserRound,
  Users,
} from "lucide-react";
import {
  createContext,
  Fragment,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { useQuery } from "@tanstack/react-query";

import { Badge } from "@/components/ui/badge";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Command,
  CommandEmpty,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { BrandMark } from "@/components/brand/BrandMark";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarRail,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { useTheme } from "@/components/theme/ThemeProvider";
import { useCurrentUser } from "@/hooks/useCurrentUser";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { api, type CurrentUser, type OrgMembership } from "@/lib/api";

type ShellContextValue = {
  user: CurrentUser;
  selectedOrg: string | null;
  setSelectedOrg: (org: string | null) => void;
};

const ShellContext = createContext<ShellContextValue | null>(null);

export function useShell() {
  const context = useContext(ShellContext);
  if (!context) {
    throw new Error("useShell must be used within AppShell.");
  }
  return context;
}

export function AppShell() {
  const { data: user, error, isLoading } = useCurrentUser();
  const [selectedOrg, setSelectedOrgState] = useState<string | null>(() => {
    if (typeof window === "undefined") {
      return null;
    }
    return localStorage.getItem("state-db-selected-org");
  });

  const unauthorized = useUnauthorizedRedirect(error);
  const setSelectedOrg = (org: string | null) => {
    setSelectedOrgState(org);
    if (org) {
      localStorage.setItem("state-db-selected-org", org);
    } else {
      localStorage.removeItem("state-db-selected-org");
    }
  };

  if (isLoading || unauthorized) {
    return (
      <div className="flex min-h-full flex-col gap-4 p-6">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (!user) {
    return null;
  }

  const context = { user, selectedOrg, setSelectedOrg };

  return (
    <ShellContext.Provider value={context}>
      <TooltipProvider>
        <SidebarProvider>
          <AppSidebar user={user} />
          <SidebarRail />
          <SidebarInset>
            <header className="flex h-14 shrink-0 items-center gap-3 border-b px-4">
              <SidebarTrigger />
              <ConsoleBreadcrumb />
              <div className="ml-auto">
                <UserMenu user={user} />
              </div>
            </header>
            <main className="min-w-0 flex-1 p-6">
              <Outlet />
            </main>
          </SidebarInset>
        </SidebarProvider>
        <Toaster richColors />
      </TooltipProvider>
    </ShellContext.Provider>
  );
}

function AppSidebar({ user }: { user: CurrentUser }) {
  const location = useLocation();
  const nav = [
    { label: "Stacks", to: "/console", icon: Database },
    { label: "Tokens", to: "/console/account/tokens", icon: KeyRound },
  ];
  const admin = [
    { label: "Providers", to: "/console/admin/providers", icon: Shield },
    { label: "Orgs", to: "/console/admin/orgs", icon: Building2 },
    { label: "Users", to: "/console/admin/users", icon: Users },
    { label: "Service tokens", to: "/console/admin/tokens", icon: KeyRound },
    { label: "Operations", to: "/console/admin/operations", icon: ServerCog },
  ];
  const ownsOrganization = user.organizations.some((org) => org.role === "owner");
  const managementItems = user.siteAdmin
    ? admin
    : admin.filter((item) => item.to === "/console/admin/tokens");

  return (
    <Sidebar>
      {/* The rule lives on the header's own box, not on a separator after it,
          so it lands on the same pixel row as the content header's border-b.
          It also takes --border rather than --sidebar-border: this is one
          continuous line across the top of the app, not a sidebar divider. */}
      <SidebarHeader className="border-b">
        <BrandMark className="size-8 shrink-0" />
        <span className="font-semibold tracking-tight group-data-[sidebar-open=false]/sidebar-wrapper:sr-only">
          state<span className="text-[var(--tide-deep)] dark:text-[var(--tide-lit)]">db</span>
        </span>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarMenu>
            {nav.map((item) => (
              <NavItem
                key={item.to}
                {...item}
                active={isActive(location.pathname, item.to)}
              />
            ))}
          </SidebarMenu>
        </SidebarGroup>
        {(user.siteAdmin || ownsOrganization) && (
          <SidebarGroup>
            <SidebarGroupLabel>Manage</SidebarGroupLabel>
            <SidebarMenu>
              {managementItems.map((item) => (
                <NavItem
                  key={item.to}
                  {...item}
                  active={isActive(location.pathname, item.to)}
                />
              ))}
            </SidebarMenu>
          </SidebarGroup>
        )}
      </SidebarContent>
      <SidebarFooter>
        <OrgSwitcher user={user} />
        {/* Static file bundled into the server jar; not served by the bun dev server. */}
        <a
          href="/THIRD-PARTY-NOTICES.txt"
          target="_blank"
          rel="noreferrer"
          className="px-2 text-xs text-muted-foreground hover:text-foreground group-data-[sidebar-open=false]/sidebar-wrapper:hidden"
        >
          Open-source licenses
        </a>
      </SidebarFooter>
    </Sidebar>
  );
}

function NavItem({
  label,
  to,
  icon: Icon,
  active,
}: {
  label: string;
  to: string;
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  active: boolean;
}) {
  return (
    <SidebarMenuItem>
      <SidebarMenuButton asChild isActive={active}>
        <Link to={to}>
          <Icon />
          <span className="truncate group-data-[sidebar-open=false]/sidebar-wrapper:sr-only">
            {label}
          </span>
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  );
}

function OrgSwitcher({ user }: { user: CurrentUser }) {
  const { selectedOrg, setSelectedOrg } = useShell();
  const navigate = useNavigate();
  const allOrgs = useQuery({ queryKey: ["admin", "orgs"], queryFn: api.admin.orgs.list, enabled: user.siteAdmin });
  const organizations: OrgMembership[] = user.siteAdmin
    ? (allOrgs.data ?? []).map(org => ({name:org.name, displayName:org.displayName, role:"admin"}))
    : user.organizations;
  const selected = organizations.find((org) => org.name === selectedOrg);

  useEffect(() => {
    // Default a single-org member into their org. Admins are excluded so
    // auto-select can't fight their "All organizations" choice.
    if (!user.siteAdmin && organizations.length === 1 && selectedOrg === null) {
      setSelectedOrg(organizations[0].name);
    }
  }, [user.siteAdmin, organizations, selectedOrg, setSelectedOrg]);

  if (user.siteAdmin && allOrgs.isPending) {
    return <Skeleton className="h-9 w-full" />;
  }

  if (organizations.length === 0) {
    return (
      <div className="rounded-md border border-sidebar-border p-2 text-sm text-muted-foreground">
        No organizations
      </div>
    );
  }

  if (!user.siteAdmin && organizations.length === 1) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-sidebar-border p-2 text-sm">
        <Building2 />
        <span className="truncate group-data-[sidebar-open=false]/sidebar-wrapper:sr-only">
          {organizations[0].displayName}
        </span>
      </div>
    );
  }

  // Admins see every org (unbounded), so they get a searchable, capped list.
  // Non-admins keep the plain dropdown, bounded by their memberships.
  if (user.siteAdmin) {
    return <AdminOrgSwitcher organizations={organizations} selected={selected} />;
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <SidebarMenuButton>
          <Building2 />
          <span className="truncate group-data-[sidebar-open=false]/sidebar-wrapper:sr-only">
            {selected?.displayName ?? "All organizations"}
          </span>
          <ChevronDown className="ml-auto group-data-[sidebar-open=false]/sidebar-wrapper:hidden" />
        </SidebarMenuButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent side="right" align="end" className="w-56">
        <DropdownMenuLabel>Organization</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem
            onClick={() => {
              setSelectedOrg(null);
              void navigate({ to: "/console" });
            }}
          >
            All organizations
          </DropdownMenuItem>
          {organizations.map((org) => (
            <DropdownMenuItem
              key={org.name}
              onClick={() => {
                setSelectedOrg(org.name);
                void navigate({ to: "/console/$org", params: { org: org.name } });
              }}
            >
              <span className="truncate">{org.displayName}</span>
              <Badge variant="outline" className="ml-auto">
                {org.role}
              </Badge>
            </DropdownMenuItem>
          ))}
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

// Cap on org rows rendered at once; search narrows past it.
const MAX_ORG_RESULTS = 50;

function AdminOrgSwitcher({
  organizations,
  selected,
}: {
  organizations: OrgMembership[];
  selected: OrgMembership | undefined;
}) {
  const { setSelectedOrg } = useShell();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");

  const query = search.trim().toLowerCase();
  const matches = useMemo(
    () =>
      query
        ? organizations.filter(
            (org) =>
              org.displayName.toLowerCase().includes(query) ||
              org.name.toLowerCase().includes(query),
          )
        : organizations,
    [organizations, query],
  );
  const shown = matches.slice(0, MAX_ORG_RESULTS);
  const hidden = matches.length - shown.length;
  const showAll = !query || "all organizations".includes(query);

  const choose = (org: string | null) => {
    setSelectedOrg(org);
    setSearch("");
    setOpen(false);
    void navigate(
      org ? { to: "/console/$org", params: { org } } : { to: "/console" },
    );
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <SidebarMenuButton>
          <Building2 />
          <span className="truncate group-data-[sidebar-open=false]/sidebar-wrapper:sr-only">
            {selected?.displayName ?? "All organizations"}
          </span>
          <ChevronDown className="ml-auto group-data-[sidebar-open=false]/sidebar-wrapper:hidden" />
        </SidebarMenuButton>
      </PopoverTrigger>
      <PopoverContent side="right" align="end" className="w-64 p-0">
        <Command>
          <CommandInput
            placeholder="Search organizations…"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <CommandList>
            {showAll && (
              <CommandItem
                className="cursor-pointer hover:bg-accent"
                onClick={() => choose(null)}
              >
                All organizations
              </CommandItem>
            )}
            {shown.map((org) => (
              <CommandItem
                key={org.name}
                className="cursor-pointer hover:bg-accent"
                onClick={() => choose(org.name)}
              >
                <span className="truncate">{org.displayName}</span>
              </CommandItem>
            ))}
            {!showAll && shown.length === 0 && (
              <CommandEmpty>No organizations found.</CommandEmpty>
            )}
            {hidden > 0 && (
              <div className="px-2 py-1.5 text-xs text-muted-foreground">
                {hidden} more — refine your search.
              </div>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}

function UserMenu({ user }: { user: CurrentUser }) {
  const { theme, setTheme, resolvedTheme } = useTheme();
  const initials = (user.name ?? user.email ?? user.id)
    .split(/[\s@._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("");

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="ghost" className="gap-2">
          <Avatar>
            <AvatarFallback>{initials || <UserRound />}</AvatarFallback>
          </Avatar>
          <span className="hidden max-w-48 truncate md:inline">
            {user.email ?? user.name ?? user.id}
          </span>
          <ChevronDown />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel className="flex flex-col gap-1">
          <span className="truncate">{user.email ?? user.name ?? user.id}</span>
          <span className="text-xs font-normal text-muted-foreground">
            {user.siteAdmin ? "Site admin" : "Console user"}
          </span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
        >
          {resolvedTheme === "dark" ? <Sun /> : <Moon />}
          {resolvedTheme === "dark" ? "Light mode" : "Dark mode"}
          {theme === "system" && (
            <Badge variant="outline" className="ml-auto">
              system
            </Badge>
          )}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          onClick={() => {
            localStorage.removeItem("state-db-selected-org");
            window.location.href = "/api/auth/logout";
          }}
        >
          <LogOut />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function ConsoleBreadcrumb() {
  const location = useLocation();
  const segments = location.pathname
    .replace(/^\/console\/?/, "")
    .split("/")
    .filter(Boolean);

  const crumbs = [
    { label: "console", to: "/console" },
    ...segments.map((segment, index) => ({
      label: decodeURIComponent(segment),
      to: `/console/${segments.slice(0, index + 1).join("/")}`,
    })),
  ];

  return (
    <Breadcrumb className="min-w-0">
      <BreadcrumbList className="truncate">
        {crumbs.map((crumb, index) => {
          const last = index === crumbs.length - 1;
          const linkable = isBreadcrumbScreen(crumb.to);
          return (
            <Fragment key={crumb.to}>
              <BreadcrumbItem className="min-w-0">
                {last ? (
                  <BreadcrumbPage className="truncate">
                    {crumb.label}
                  </BreadcrumbPage>
                ) : linkable ? (
                  <BreadcrumbLink asChild>
                    <Link to={crumb.to} className="truncate">
                      {crumb.label}
                    </Link>
                  </BreadcrumbLink>
                ) : (
                  <span className="truncate">{crumb.label}</span>
                )}
              </BreadcrumbItem>
              {!last && <BreadcrumbSeparator />}
            </Fragment>
          );
        })}
      </BreadcrumbList>
    </Breadcrumb>
  );
}

function isActive(pathname: string, to: string) {
  if (to === "/console") {
    const firstSegment = getFirstConsoleSegment(pathname);
    return (
      pathname === "/console" ||
      (pathname.startsWith("/console/") &&
        firstSegment !== null &&
        !["account", "admin", "login"].includes(firstSegment))
    );
  }
  return pathname === to || pathname.startsWith(`${to}/`);
}

function getFirstConsoleSegment(pathname: string) {
  const [firstSegment] = pathname
    .replace(/^\/console\/?/, "")
    .split("/")
    .filter(Boolean);
  return firstSegment ?? null;
}

function isBreadcrumbScreen(to: string) {
  if (to === "/console" || to === "/console/admin") {
    return true;
  }
  if (to === "/console/account") {
    return false;
  }
  if (/^\/console\/[^/]+$/.test(to)) {
    return false;
  }
  if (to.endsWith("/updates") || to.endsWith("/previews")) {
    return false;
  }
  return true;
}
