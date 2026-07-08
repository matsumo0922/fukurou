import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import CheckCircle2 from "lucide-react/dist/esm/icons/check-circle-2.mjs";
import GitCompareArrows from "lucide-react/dist/esm/icons/git-compare-arrows.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import RotateCcw from "lucide-react/dist/esm/icons/rotate-ccw.mjs";
import Save from "lucide-react/dist/esm/icons/save.mjs";
import SlidersHorizontal from "lucide-react/dist/esm/icons/sliders-horizontal.mjs";
import {
  activateRuntimeConfigDraft,
  createRuntimeConfigDraft,
  opsRuntimeConfigQuery,
  rollbackRuntimeConfigVersion,
  validateRuntimeConfigDraft,
  type RuntimeConfigGroup,
  type RuntimeConfigItem,
  type RuntimeConfigSnapshot,
  type RuntimeConfigValidationError,
  type RuntimeConfigValidationResult,
  type RuntimeConfigVersionDetail,
} from "../api/ops";
import type { MessageKey } from "../i18n/messages";
import { useI18n } from "../i18n/useI18n";
import { EmptyState } from "../ui/components/EmptyState";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError, formatDateTime } from "../ui/format";

type DraftValues = Record<string, string>;

type DraftChange = {
  key: string;
  beforeValue: string;
  afterValue: string;
};

type OperationState = {
  tone: StatusTone;
  messageKey: MessageKey;
};

export function ConfigPage() {
  const configQuery = useQuery(opsRuntimeConfigQuery);
  const queryClient = useQueryClient();
  const { t } = useI18n();
  const [draftValues, setDraftValues] = useState<DraftValues>({});
  const [draftDetail, setDraftDetail] = useState<RuntimeConfigVersionDetail | null>(null);
  const [operationState, setOperationState] = useState<OperationState | null>(null);
  const draftChanges = useMemo(
    () => collectDraftChanges(configQuery.data?.groups ?? [], draftValues),
    [configQuery.data?.groups, draftValues],
  );
  const draftPatch = useMemo(
    () =>
      draftChanges.reduce<DraftValues>((values, change) => {
        values[change.key] = change.afterValue;

        return values;
      }, {}),
    [draftChanges],
  );
  const createDraftMutation = useMutation({
    mutationFn: () => createRuntimeConfigDraft({ values: draftPatch }),
    onSuccess: (detail) => {
      setDraftDetail(detail);
      setOperationState({
        tone: detail.validation.valid ? "positive" : "warning",
        messageKey: detail.validation.valid ? "config.operation.draftSaved" : "config.operation.draftSavedInvalid",
      });
      void queryClient.invalidateQueries({ queryKey: opsRuntimeConfigQuery.queryKey });
    },
  });
  const validateDraftMutation = useMutation({
    mutationFn: (versionId: string) => validateRuntimeConfigDraft(versionId),
    onSuccess: (detail) => {
      setDraftDetail(detail);
      setOperationState({
        tone: detail.validation.valid ? "positive" : "warning",
        messageKey: detail.validation.valid ? "config.operation.validationPassed" : "config.operation.validationFailed",
      });
    },
  });
  const activateDraftMutation = useMutation({
    mutationFn: (versionId: string) => activateRuntimeConfigDraft(versionId),
    onSuccess: (response) => {
      if (response.status === 409) {
        setDraftDetail((detail) => (detail ? { ...detail, validation: response.data as RuntimeConfigValidationResult } : detail));
        setOperationState({ tone: "critical", messageKey: "config.operation.activationRejected" });

        return;
      }

      setDraftDetail(null);
      setDraftValues({});
      setOperationState({ tone: "positive", messageKey: "config.operation.activated" });
      void queryClient.invalidateQueries({ queryKey: opsRuntimeConfigQuery.queryKey });
    },
  });
  const rollbackMutation = useMutation({
    mutationFn: (versionId: string) => rollbackRuntimeConfigVersion(versionId),
    onSuccess: (response) => {
      if (response.status === 409) {
        setOperationState({ tone: "critical", messageKey: "config.operation.rollbackRejected" });

        return;
      }

      setDraftDetail(null);
      setDraftValues({});
      setOperationState({ tone: "positive", messageKey: "config.operation.rolledBack" });
      void queryClient.invalidateQueries({ queryKey: opsRuntimeConfigQuery.queryKey });
    },
  });
  const isMutating =
    createDraftMutation.isPending ||
    validateDraftMutation.isPending ||
    activateDraftMutation.isPending ||
    rollbackMutation.isPending;
  const mutationError =
    createDraftMutation.error ||
    validateDraftMutation.error ||
    activateDraftMutation.error ||
    rollbackMutation.error;

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="Config"
        description={t("config.description")}
        action={
          <div className="config-actions">
            <button
              className="icon-text-button icon-text-button--neutral"
              type="button"
              onClick={() => void configQuery.refetch()}
              disabled={configQuery.isFetching || isMutating}
            >
              <RefreshCw size={16} aria-hidden="true" />
              {configQuery.isFetching ? t("common.refreshing") : t("common.refresh")}
            </button>
            <button
              className="icon-text-button icon-text-button--prominent"
              type="button"
              onClick={() => createDraftMutation.mutate()}
              disabled={draftChanges.length === 0 || isMutating}
            >
              <Save size={16} aria-hidden="true" />
              {t("config.action.saveDraft")}
            </button>
            <button
              className="icon-text-button icon-text-button--neutral"
              type="button"
              onClick={() => draftDetail && validateDraftMutation.mutate(draftDetail.version.id)}
              disabled={!draftDetail || isMutating}
            >
              <CheckCircle2 size={16} aria-hidden="true" />
              {t("config.action.validateDraft")}
            </button>
            <button
              className="icon-text-button icon-text-button--warning"
              type="button"
              onClick={() => draftDetail && activateDraftMutation.mutate(draftDetail.version.id)}
              disabled={!draftDetail || !draftDetail.validation.valid || isMutating}
            >
              <CheckCircle2 size={16} aria-hidden="true" />
              {t("config.action.activateDraft")}
            </button>
          </div>
        }
      />

      {configQuery.isPending ? <ConfigLoading /> : null}
      {configQuery.isError ? <ConfigError error={configQuery.error} retried={() => void configQuery.refetch()} /> : null}
      {mutationError ? <ConfigMutationError error={mutationError} /> : null}
      {configQuery.data ? (
        <>
          <ConfigVersionPanel
            snapshot={configQuery.data}
            rollbackVersion={(versionId) => rollbackMutation.mutate(versionId)}
            disabled={isMutating}
          />
          <ConfigDraftPanel
            changes={draftChanges}
            draftDetail={draftDetail}
            operationState={operationState}
          />
          <ConfigCatalog
            groups={configQuery.data.groups}
            draftValues={draftValues}
            onDraftValueChange={(key, value) => {
              setDraftValues((currentValues) => ({
                ...currentValues,
                [key]: value,
              }));
              setDraftDetail(null);
              setOperationState(null);
            }}
          />
        </>
      ) : null}
    </div>
  );
}

function ConfigVersionPanel({
  snapshot,
  rollbackVersion,
  disabled,
}: {
  snapshot: RuntimeConfigSnapshot;
  rollbackVersion: (versionId: string) => void;
  disabled: boolean;
}) {
  const { locale, t } = useI18n();
  const versions = snapshot.versions ?? [];

  return (
    <Panel className="panel--wide">
      <div className="panel-heading">
        <SlidersHorizontal size={18} aria-hidden="true" />
        <h2>{t("config.version.heading")}</h2>
        <StatusPill label={snapshot.activeVersion?.status ?? t("common.none")} tone="positive" />
      </div>
      <div className="config-version-list">
        {versions.length === 0 ? <span className="config-version-list__empty">{t("common.noRecords")}</span> : null}
        {versions.map((version) => (
          <div className="config-version" key={version.id}>
            <div className="config-version__main">
              <span className="config-version__id">{shortVersionId(version.id)}</span>
              <StatusPill label={version.status} tone={version.status === "ACTIVE" ? "positive" : "neutral"} />
              <span>{formatDateTime(version.createdAt, locale)}</span>
            </div>
            <div className="config-version__meta">
              <span>{version.createdBy}</span>
              <span>{shortVersionId(version.hash)}</span>
              {version.note ? <span>{version.note}</span> : null}
            </div>
            {version.status === "INACTIVE" ? (
              <button
                className="icon-text-button icon-text-button--neutral"
                type="button"
                onClick={() => rollbackVersion(version.id)}
                disabled={disabled}
              >
                <RotateCcw size={16} aria-hidden="true" />
                {t("config.action.rollback")}
              </button>
            ) : null}
          </div>
        ))}
      </div>
    </Panel>
  );
}

function ConfigDraftPanel({
  changes,
  draftDetail,
  operationState,
}: {
  changes: DraftChange[];
  draftDetail: RuntimeConfigVersionDetail | null;
  operationState: OperationState | null;
}) {
  const { t } = useI18n();
  const validation = draftDetail?.validation ?? null;
  const validationErrors = validation?.errors ?? [];

  if (changes.length === 0 && !draftDetail && !operationState) {
    return null;
  }

  return (
    <Panel className="panel--wide">
      <div className="panel-heading">
        <GitCompareArrows size={18} aria-hidden="true" />
        <h2>{t("config.draft.heading")}</h2>
        <StatusPill label={validation?.valid ? t("config.validation.valid") : t("config.validation.pending")} tone={validation?.valid ? "positive" : "warning"} />
      </div>
      {operationState ? (
        <div className="config-operation-state">
          <StatusPill label={t(operationState.messageKey)} tone={operationState.tone} />
        </div>
      ) : null}
      {changes.length > 0 ? (
        <div className="config-diff-list">
          {changes.map((change) => (
            <div className="config-diff" key={change.key}>
              <span className="config-diff__key">{change.key}</span>
              <span>{change.beforeValue}</span>
              <span>{change.afterValue}</span>
            </div>
          ))}
        </div>
      ) : null}
      {validationErrors.length ? (
        <div className="config-validation-list">
          {validationErrors.map((error, index) => (
            <span className="config-validation-error" key={`${error.code}-${error.key ?? "global"}-${index}`}>
              {validationErrorLabel(error, t)}
            </span>
          ))}
        </div>
      ) : null}
    </Panel>
  );
}

function ConfigCatalog({
  groups,
  draftValues,
  onDraftValueChange,
}: {
  groups: RuntimeConfigGroup[];
  draftValues: DraftValues;
  onDraftValueChange: (key: string, value: string) => void;
}) {
  return (
    <div className="page-grid">
      {groups.map((group) => (
        <ConfigGroupPanel
          key={group.id}
          group={group}
          draftValues={draftValues}
          onDraftValueChange={onDraftValueChange}
        />
      ))}
    </div>
  );
}

function ConfigGroupPanel({
  group,
  draftValues,
  onDraftValueChange,
}: {
  group: RuntimeConfigGroup;
  draftValues: DraftValues;
  onDraftValueChange: (key: string, value: string) => void;
}) {
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
          <span role="columnheader">{t("config.table.draft")}</span>
          <span role="columnheader">{t("config.table.metadata")}</span>
        </div>
        {group.items.map((item) => (
          <ConfigItemRow
            key={item.key}
            item={item}
            draftValue={draftValues[item.key] ?? item.effectiveValue ?? ""}
            onDraftValueChange={onDraftValueChange}
          />
        ))}
      </div>
    </Panel>
  );
}

function ConfigItemRow({
  item,
  draftValue,
  onDraftValueChange,
}: {
  item: RuntimeConfigItem;
  draftValue: string;
  onDraftValueChange: (key: string, value: string) => void;
}) {
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
      <ConfigDraftCell
        item={item}
        value={draftValue}
        onChange={(value) => onDraftValueChange(item.key, value)}
      />
      <div className="config-table__metadata" role="cell">
        <StatusPill label={item.sourceKind} tone={sourceTone(item.sourceKind)} />
        <StatusPill label={item.valueType} tone="neutral" />
        <StatusPill label={item.applyMode} tone="neutral" />
        <StatusPill label={item.editable ? t("config.status.editable") : t("config.status.readOnly")} tone={item.editable ? "positive" : "warning"} />
        <StatusPill label={item.safetyTier} tone={safetyTierTone(item.safetyTier)} />
      </div>
    </div>
  );
}

function ConfigDraftCell({
  item,
  value,
  onChange,
}: {
  item: RuntimeConfigItem;
  value: string;
  onChange: (value: string) => void;
}) {
  const { t } = useI18n();

  if (!item.editable || item.sourceKind !== "RUNTIME") {
    return <ConfigValueCell item={item} value={item.effectiveValue ?? null} effective />;
  }

  if (item.valueType === "BOOLEAN") {
    return (
      <label className="config-draft-toggle" role="cell">
        <input
          type="checkbox"
          checked={value === "true"}
          onChange={(event) => onChange(event.target.checked ? "true" : "false")}
        />
        <span>{value === "true" ? t("common.yes") : t("common.no")}</span>
      </label>
    );
  }

  if (item.valueType === "STRUCTURED_JSON_LIST" || item.valueType === "STRING_LIST") {
    return (
      <textarea
        className="config-draft-input config-draft-input--multiline"
        aria-label={translatedOrFallback(item.labelKey, item.key, t)}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        rows={3}
      />
    );
  }

  return (
    <input
      className="config-draft-input"
      aria-label={translatedOrFallback(item.labelKey, item.key, t)}
      value={value}
      inputMode={item.valueType === "INT" || item.valueType === "DURATION_SECONDS" || item.valueType === "DECIMAL_STRING" ? "decimal" : "text"}
      onChange={(event) => onChange(event.target.value)}
    />
  );
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

function ConfigMutationError({ error }: { error: unknown }) {
  const { locale, t } = useI18n();

  return (
    <Panel>
      <EmptyState
        title={t("config.error.operationFailed")}
        description={describeError(error, locale)}
      />
    </Panel>
  );
}

function translatedOrFallback(key: string, fallback: string, t: (key: MessageKey) => string): string {
  const translated = t(key as MessageKey);

  return translated === key ? fallback : translated;
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

function collectDraftChanges(groups: RuntimeConfigGroup[], draftValues: DraftValues): DraftChange[] {
  return groups
    .flatMap((group) => group.items)
    .filter((item) => item.editable && item.sourceKind === "RUNTIME")
    .flatMap((item) => {
      const beforeValue = item.effectiveValue ?? "";
      const afterValue = draftValues[item.key] ?? beforeValue;

      return beforeValue === afterValue
        ? []
        : [
            {
              key: item.key,
              beforeValue,
              afterValue,
            },
          ];
    });
}

function validationErrorLabel(error: RuntimeConfigValidationError, t: (key: MessageKey) => string): string {
  const translated = t(error.code as MessageKey);
  const fallback = [error.key, error.code].filter(Boolean).join(": ");
  const template = translated === error.code ? fallback : translated;

  return template.replace(/\{([^}]+)\}/g, (_, key: string) => {
    if (key === "key") {
      return error.key ?? "";
    }

    return error.params?.[key] ?? "";
  });
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

function shortVersionId(value: string): string {
  return value.length <= 12 ? value : value.slice(0, 12);
}
