import type { LucideIcon } from "lucide-react";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import LayoutDashboard from "lucide-react/dist/esm/icons/layout-dashboard.mjs";
import Power from "lucide-react/dist/esm/icons/power.mjs";
import ServerCog from "lucide-react/dist/esm/icons/server-cog.mjs";
import ShieldAlert from "lucide-react/dist/esm/icons/shield-alert.mjs";
import SlidersHorizontal from "lucide-react/dist/esm/icons/sliders-horizontal.mjs";
import { NavLink } from "react-router";
import type { MessageKey } from "../../i18n/messages";
import { useI18n } from "../../i18n/useI18n";

type NavigationItem = {
  label: string;
  path: string;
  icon: LucideIcon;
};

type NavigationSection = {
  labelKey: MessageKey;
  items: NavigationItem[];
};

const navigationSections: NavigationSection[] = [
  {
    labelKey: "sidebar.read",
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
        label: "Config",
        path: "/app/config",
        icon: SlidersHorizontal,
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
    labelKey: "sidebar.operate",
    items: [
      {
        label: "Controls",
        path: "/app/controls",
        icon: Power,
      },
    ],
  },
];

export function SidebarNav() {
  const { t } = useI18n();

  return (
    <aside className="sidebar" aria-label={t("sidebar.primaryNavigation")}>
      <div className="sidebar__brand">
        <span className="sidebar__mark" aria-hidden="true">
          🦉
        </span>
        <div>
          <p className="sidebar__eyebrow">Fukurou</p>
          <p className="sidebar__title">Operations</p>
        </div>
      </div>

      <nav className="sidebar__nav" aria-label={t("sidebar.appSections")}>
        {navigationSections.map((section) => (
          <div className="sidebar__section" key={section.labelKey}>
            <p className="sidebar__section-label">{t(section.labelKey)}</p>
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
