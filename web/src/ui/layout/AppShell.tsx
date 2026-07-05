import { Outlet } from "react-router";
import { SidebarNav } from "./SidebarNav";
import { TopStatusBar } from "./TopStatusBar";

export function AppShell() {
  return (
    <div className="app-shell">
      <SidebarNav />
      <div className="app-shell__workspace">
        <TopStatusBar />
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
