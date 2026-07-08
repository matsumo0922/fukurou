import { useQuery } from "@tanstack/react-query";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import SlidersHorizontal from "lucide-react/dist/esm/icons/sliders-horizontal.mjs";
import { opsRuntimeConfigQuery, type RuntimeConfigGroup, type RuntimeConfigItem } from "../api/ops";
import type { MessageKey } from "../i18n/messages";
import { useI18n } from "../i18n/useI18n";
import { EmptyState } from "../ui/components/EmptyState";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError } from "../ui/format";

export function ConfigPage() {
  const configQuery = useQuery(opsRuntimeConfigQuery);
  const { t } = useI18n();

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="Config"
        description={t("config.description")}
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={() => void configQuery.refetch()}
            disabled={configQuery.isFetching}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {configQuery.isFetching ? t("common.refreshing") : t("common.refresh")}
          </button>
        }
      />

      {configQuery.isPending ? <ConfigLoading /> : null}
      {configQuery.isError ? <ConfigError error={configQuery.error} retried={() => void configQuery.refetch()} /> : null}
      {configQuery.data ? <ConfigCatalog groups={configQuery.data.groups} /> : null}
    </div>
  );
}

function ConfigCatalog({ groups }: { groups: RuntimeConfigGroup[] }) {
  return (
    <div className="page-grid">
      {groups.map((group) => (
        <ConfigGroupPanel key={group.id} group={group} />
      ))}
    </div>
  );
}

function ConfigGroupPanel({ group }: { group: RuntimeConfigGroup }) {
  const { t } = useI18n();

  return (
    <Panel className="panel--wide">
      <div className="panel-heading">
        <SlidersHorizontal size={18} aria-hidden="true" />
        <h2>{t(group.labelKey as MessageKey)}</h2>
        <StatusPill label={`${group.items.length}`} tone="neutral" />
      </div>
      <p className="config-group-description">{t(group.descriptionKey as MessageKey)}</p>
      <div className="config-table" role="table" aria-label={t(group.labelKey as MessageKey)}>
        <div className="config-table__row config-table__row--head" role="row">
          <span role="columnheader">{t("config.table.item")}</span>
          <span role="columnheader">{t("config.table.default")}</span>
          <span role="columnheader">{t("config.table.current")}</span>
          <span role="columnheader">{t("config.table.effective")}</span>
          <span role="columnheader">{t("config.table.metadata")}</span>
        </div>
        {group.items.map((item) => (
          <ConfigItemRow key={item.key} item={item} />
        ))}
      </div>
    </Panel>
  );
}

function ConfigItemRow({ item }: { item: RuntimeConfigItem }) {
  const { t } = useI18n();
  const label = translatedOrFallback(item.labelKey, item.legacyEnvName, t);
  const description = translatedOrFallback(item.descriptionKey, item.key, t);

  return (
    <div className="config-table__row" role="row">
      <div className="config-table__item" role="cell">
        <span className="config-table__label">{label}</span>
        <span className="config-table__description">{description}</span>
        <span className="config-table__env">{item.legacyEnvName}</span>
      </div>
      <ConfigValueCell item={item} value={item.defaultValue ?? null} />
      <ConfigValueCell item={item} value={item.currentValue ?? null} current />
      <ConfigValueCell item={item} value={item.effectiveValue ?? null} effective />
      <div className="config-table__metadata" role="cell">
        <StatusPill label={item.sourceKind} tone={sourceTone(item.sourceKind)} />
        <StatusPill label={item.valueType} tone="neutral" />
        <StatusPill label={item.applyMode} tone="neutral" />
        <StatusPill label={item.editable ? t("config.status.editable") : t("config.status.readOnly")} tone="warning" />
        <StatusPill label={item.safetyTier} tone={safetyTierTone(item.safetyTier)} />
      </div>
    </div>
  );
}

function translatedOrFallback(key: string, fallback: string, t: (key: MessageKey) => string): string {
  const translated = t(key as MessageKey);

  return translated === key ? fallback : translated;
}

function ConfigValueCell({
  item,
  value,
  current = false,
  effective = false,
}: {
  item: RuntimeConfigItem;
  value: string | null;
  current?: boolean;
  effective?: boolean;
}) {
  const { t } = useI18n();
  const label = valueLabel(item, value, current, effective, t);

  return (
    <span className="config-table__value" role="cell">
      {label}
      {value && item.unit ? <span className="config-table__unit">{item.unit}</span> : null}
    </span>
  );
}

function ConfigLoading() {
  const { t } = useI18n();

  return (
    <Panel>
      <div className="loading-row" role="status">
        <span className="loading-dot" aria-hidden="true" />
        <span>{t("config.loading.catalog")}</span>
      </div>
    </Panel>
  );
}

function ConfigError({ error, retried }: { error: unknown; retried: () => void }) {
  const { locale, t } = useI18n();

  return (
    <Panel>
      <EmptyState
        title={t("config.error.catalogUnavailable")}
        description={describeError(error, locale)}
        action={
          <button className="icon-text-button icon-text-button--prominent" type="button" onClick={retried}>
            <RefreshCw size={16} aria-hidden="true" />
            {t("common.retry")}
          </button>
        }
      />
    </Panel>
  );
}

function valueLabel(
  item: RuntimeConfigItem,
  value: string | null,
  current: boolean,
  effective: boolean,
  t: (key: MessageKey) => string,
): string {
  if (item.sourceKind === "SECRET") {
    return item.valueConfigured ? t("config.secret.configured") : t("config.secret.missing");
  }

  if (value) {
    return value;
  }

  if (current) {
    return t("config.value.notSet");
  }

  if (effective && item.valueConfigured) {
    return t("common.notReported");
  }

  return t("common.none");
}

function sourceTone(sourceKind: RuntimeConfigItem["sourceKind"]): StatusTone {
  if (sourceKind === "RUNTIME") {
    return "positive";
  }

  if (sourceKind === "SECRET") {
    return "critical";
  }

  return "neutral";
}

function safetyTierTone(safetyTier: RuntimeConfigItem["safetyTier"]): StatusTone {
  if (safetyTier === "SAFETY_CRITICAL" || safetyTier === "SECRET") {
    return "critical";
  }

  if (safetyTier === "GUARDED" || safetyTier === "DEPLOYMENT_BOUNDARY") {
    return "warning";
  }

  return "neutral";
}
