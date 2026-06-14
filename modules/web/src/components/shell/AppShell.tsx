import { Outlet } from "@tanstack/react-router";
import { Database } from "lucide-react";

export function AppShell() {
  return (
    <div className="flex min-h-full flex-col">
      <header className="flex h-14 items-center gap-2 border-b px-6">
        <Database className="size-5 text-muted-foreground" />
        <span className="font-semibold tracking-tight">state-db</span>
      </header>
      <main className="flex-1 p-6">
        <Outlet />
      </main>
    </div>
  );
}
