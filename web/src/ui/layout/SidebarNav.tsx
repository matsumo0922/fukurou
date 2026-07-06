import type { LucideIcon } from "lucide-react";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import LayoutDashboard from "lucide-react/dist/esm/icons/layout-dashboard.mjs";
import ServerCog from "lucide-react/dist/esm/icons/server-cog.mjs";
import ShieldAlert from "lucide-react/dist/esm/icons/shield-alert.mjs";
import { NavLink } from "react-router";

type NavigationItem = {
  label: string;
  path: string;
  icon: LucideIcon;
};

type NavigationSection = {
  label: string;
  items: NavigationItem[];
};

const navigationSections: NavigationSection[] = [
  {
    label: "Read",
    items: [
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
        label: "Evaluation",
        path: "/app/evaluation",
        icon: ShieldAlert,
      },
      {
        label: "System",
        path: "/app/system",
        icon: ServerCog,
      },
    ],
  },
  {
    label: "Operate",
    items: [
      {
        label: "Controls",
        path: "/app/controls",
        icon: ShieldAlert,
      },
    ],
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

      <nav className="sidebar__nav" aria-label="App sections">
        {navigationSections.map((section) => (
          <div className="sidebar__section" key={section.label}>
            <p className="sidebar__section-label">{section.label}</p>
            <div className="sidebar__section-links">
              {section.items.map((item) => (
                <NavLink className={navLinkClassName} key={item.path} to={item.path}>
                  <item.icon size={17} aria-hidden="true" />
                  <span>{item.label}</span>
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>
    </aside>
  );
}

function navLinkClassName({ isActive }: { isActive: boolean }): string {
  return ["sidebar__link", isActive ? "sidebar__link--active" : null].filter(Boolean).join(" ");
}
