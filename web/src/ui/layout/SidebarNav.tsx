import { Activity, LayoutDashboard, ServerCog } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { NavLink } from "react-router";

type NavigationItem = {
  label: string;
  path: string;
  icon: LucideIcon;
};

const navigationItems: NavigationItem[] = [
  {
    label: "Overview",
    path: "/app/overview",
    icon: LayoutDashboard,
  },
  {
    label: "Activity",
    path: "/app/activity",
    icon: Activity,
  },
  {
    label: "System",
    path: "/app/system",
    icon: ServerCog,
  },
];

export function SidebarNav() {
  return (
    <aside className="sidebar" aria-label="Primary navigation">
      <div className="sidebar__brand">
        <img className="sidebar__mark" src="/fukurou-mark.svg" alt="" aria-hidden="true" />
        <div>
          <p className="sidebar__eyebrow">Fukurou</p>
          <p className="sidebar__title">Operations</p>
        </div>
      </div>

      <nav className="sidebar__nav">
        {navigationItems.map((item) => (
          <NavLink className={navLinkClassName} key={item.path} to={item.path}>
            <item.icon size={17} aria-hidden="true" />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
    </aside>
  );
}

function navLinkClassName({ isActive }: { isActive: boolean }): string {
  return ["sidebar__link", isActive ? "sidebar__link--active" : null].filter(Boolean).join(" ");
}
