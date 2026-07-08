import { useMutation, useQuery, useQueryClient, type QueryClient, type UseQueryResult } from "@tanstack/react-query";
import { useState, type ChangeEvent, type FormEvent } from "react";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import AlertTriangle from "lucide-react/dist/esm/icons/alert-triangle.mjs";
import KeyRound from "lucide-react/dist/esm/icons/key-round.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import ShieldAlert from "lucide-react/dist/esm/icons/shield-alert.mjs";
import { ApiClientError } from "../api/client";
import {
  LLM_AUTH_PROVIDERS,
  opsLlmAuthLoginSessionQuery,
  opsPositionsQuery,
  opsRiskStateQuery,
  requestOpsLlmAuthLogin,
  requestOpsHalt,
  requestOpsResume,
  requestOpsTrigger,
  type LlmAuthProvider,
  type OpsLlmAuthLoginResponse,
  type OpsPositionsResponse,
  type OpsRiskStateResponse,
} from "../api/ops";
import type { MessageKey } from "../i18n/messages";
import { useI18n } from "../i18n/useI18n";
import { DataStrip } from "../ui/components/DataStrip";
import { EmptyState } from "../ui/components/EmptyState";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError, formatDateTime } from "../ui/format";
import { formatBtc, formatRatioAsPercent, formatSignedJpy } from "../ui/numberFormat";

type ControlNotice = {
  tone: StatusTone;
  title: string;
  detail: string;
};

type ActiveLlmAuthLogin = {
  provider: LlmAuthProvider;
  session: OpsLlmAuthLoginResponse;
};

type ActionButtonTone = "neutral" | "warning" | "critical";
type Translate = (key: MessageKey) => string;

type SafetyActionFormProps = {
  id: string;
  title: string;
  description: string;
  badgeLabel: string;
  badgeTone: StatusTone;
  reasonLabel: string;
  reasonPlaceholder: string;
  reviewLabel: string;
  confirmLabel: string;
  pendingLabel: string;
  buttonTone: ActionButtonTone;
  isPending: boolean;
  isDisabled: boolean;
  submitted: (reason: string) => void;
};

export function ControlsPage() {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const riskStateQuery = useQuery(opsRiskStateQuery);
  const positionsQuery = useQuery(opsPositionsQuery);
  const [notice, setNotice] = useState<ControlNotice | null>(null);
  const [activeLlmAuthLogin, setActiveLlmAuthLogin] = useState<ActiveLlmAuthLogin | null>(null);
  const llmAuthLoginSessionQuery = useQuery({
    ...opsLlmAuthLoginSessionQuery(
      activeLlmAuthLogin?.provider ?? "claude",
      activeLlmAuthLogin?.session.sessionId ?? "",
    ),
    enabled: activeLlmAuthLogin !== null,
  });

  const refreshAfterSuccess = () => {
    void refreshControlsData(queryClient);
  };
  const softHaltMutation = useMutation({
    mutationFn: (reason: string) => requestOpsHalt("SOFT", reason),
    onSuccess: (riskState) => {
      setNotice({
        tone: "warning",
        title: t("controls.notice.softHaltSet"),
        detail: formatMessage(t("controls.notice.riskRefreshing"), {
          state: riskState.state,
        }),
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: t("controls.notice.softHaltFailed"),
        detail: describeControlError(error, t),
      });
    },
  });
  const hardHaltMutation = useMutation({
    mutationFn: (reason: string) => requestOpsHalt("HARD", reason),
    onSuccess: (riskState) => {
      setNotice({
        tone: "critical",
        title: t("controls.notice.hardHaltSet"),
        detail: formatMessage(t("controls.notice.riskRefreshing"), {
          state: riskState.state,
        }),
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: t("controls.notice.hardHaltFailed"),
        detail: describeControlError(error, t),
      });
    },
  });
  const resumeMutation = useMutation({
    mutationFn: requestOpsResume,
    onSuccess: (riskState) => {
      setNotice({
        tone: "positive",
        title: t("controls.notice.resumeRequested"),
        detail: formatMessage(t("controls.notice.riskRefreshing"), {
          state: riskState.state,
        }),
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: t("controls.notice.resumeFailed"),
        detail: describeControlError(error, t),
      });
    },
  });
  const triggerMutation = useMutation({
    mutationFn: requestOpsTrigger,
    onSuccess: (trigger) => {
      setNotice({
        tone: "positive",
        title: t("controls.notice.manualTriggerAcceptedTitle"),
        detail: formatMessage(t("controls.notice.manualTriggerAccepted"), {
          invocationId: trigger.invocationId,
          triggerKind: trigger.triggerKind,
        }),
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: t("controls.notice.manualTriggerFailed"),
        detail: describeControlError(error, t),
      });
    },
  });
  const llmAuthLoginMutation = useMutation({
    mutationFn: requestOpsLlmAuthLogin,
    onSuccess: (session, variables) => {
      setActiveLlmAuthLogin({
        provider: variables.provider,
        session,
      });
      setNotice({
        tone: "positive",
        title: t("controls.notice.llmAuthLoginStartedTitle"),
        detail: formatMessage(t("controls.notice.llmAuthLoginStarted"), {
          provider: providerDisplayName(variables.provider),
          sessionId: session.sessionId,
        }),
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: t("controls.notice.llmAuthLoginFailed"),
        detail: describeControlError(error, t),
      });
    },
  });
  const isRefreshing = riskStateQuery.isFetching || positionsQuery.isFetching || llmAuthLoginSessionQuery.isFetching;
  const isOperationInFlight =
    softHaltMutation.isPending ||
    hardHaltMutation.isPending ||
    resumeMutation.isPending ||
    triggerMutation.isPending ||
    llmAuthLoginMutation.isPending;
  const refreshed = () => {
    void riskStateQuery.refetch();
    void positionsQuery.refetch();
  };

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="Operate"
        title="Controls"
        description={t("controls.description")}
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={refreshed}
            disabled={isRefreshing}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {isRefreshing ? t("common.refreshing") : t("common.refresh")}
          </button>
        }
      />

      {notice ? <ControlNoticePanel notice={notice} /> : null}

      <div className="page-grid page-grid--two">
        <ControlsRiskStatePanel riskStateQuery={riskStateQuery} />
        <ControlsExposurePanel positionsQuery={positionsQuery} />
      </div>

      <Panel className="panel--wide">
        <div className="panel-heading">
          <ShieldAlert size={18} aria-hidden="true" />
          <h2>{t("controls.panel.haltControls")}</h2>
        </div>
        <HaltSemantics />
        <div className="control-action-grid control-action-grid--two">
          <SafetyActionForm
            id="soft-halt"
            title={t("controls.action.soft.title")}
            description={t("controls.action.soft.description")}
            badgeLabel="SOFT_HALT"
            badgeTone="warning"
            reasonLabel={t("controls.action.soft.reason")}
            reasonPlaceholder={t("controls.action.soft.placeholder")}
            reviewLabel={t("controls.action.soft.review")}
            confirmLabel={t("controls.action.soft.confirm")}
            pendingLabel={t("controls.action.soft.pending")}
            buttonTone="warning"
            isPending={softHaltMutation.isPending}
            isDisabled={isOperationInFlight}
            submitted={(reason) => softHaltMutation.mutate(reason)}
          />
          <SafetyActionForm
            id="hard-halt"
            title={t("controls.action.hard.title")}
            description={t("controls.action.hard.description")}
            badgeLabel="HARD_HALT"
            badgeTone="critical"
            reasonLabel={t("controls.action.hard.reason")}
            reasonPlaceholder={t("controls.action.hard.placeholder")}
            reviewLabel={t("controls.action.hard.review")}
            confirmLabel={t("controls.action.hard.confirm")}
            pendingLabel={t("controls.action.hard.pending")}
            buttonTone="critical"
            isPending={hardHaltMutation.isPending}
            isDisabled={isOperationInFlight}
            submitted={(reason) => hardHaltMutation.mutate(reason)}
          />
        </div>
      </Panel>

      <div className="page-grid page-grid--two">
        <Panel>
          <div className="panel-heading">
            <RefreshCw size={18} aria-hidden="true" />
            <h2>{t("controls.panel.resumeControl")}</h2>
          </div>
          <SafetyActionForm
            id="resume"
            title={t("controls.action.resume.title")}
            description={t("controls.action.resume.description")}
            badgeLabel="RUNNING"
            badgeTone="positive"
            reasonLabel={t("controls.action.resume.reason")}
            reasonPlaceholder={t("controls.action.resume.placeholder")}
            reviewLabel={t("controls.action.resume.review")}
            confirmLabel={t("controls.action.resume.confirm")}
            pendingLabel={t("controls.action.resume.pending")}
            buttonTone="neutral"
            isPending={resumeMutation.isPending}
            isDisabled={isOperationInFlight}
            submitted={(reason) => resumeMutation.mutate(reason)}
          />
        </Panel>

        <Panel>
          <div className="panel-heading">
            <Activity size={18} aria-hidden="true" />
            <h2>{t("controls.panel.manualTrigger")}</h2>
          </div>
          <SafetyActionForm
            id="manual-trigger"
            title={t("controls.action.manual.title")}
            description={t("controls.action.manual.description")}
            badgeLabel="MANUAL"
            badgeTone="neutral"
            reasonLabel={t("controls.action.manual.reason")}
            reasonPlaceholder={t("controls.action.manual.placeholder")}
            reviewLabel={t("controls.action.manual.review")}
            confirmLabel={t("controls.action.manual.confirm")}
            pendingLabel={t("controls.action.manual.pending")}
            buttonTone="neutral"
            isPending={triggerMutation.isPending}
            isDisabled={isOperationInFlight}
            submitted={(reason) => triggerMutation.mutate(reason)}
          />
        </Panel>
      </div>

      <Panel className="panel--wide">
        <div className="panel-heading">
          <KeyRound size={18} aria-hidden="true" />
          <h2>{t("controls.panel.llmAuth")}</h2>
        </div>
        <div className="control-action-grid control-action-grid--two">
          {LLM_AUTH_PROVIDERS.map((provider) => {
            const displayName = providerDisplayName(provider);
            const providerIsPending = llmAuthLoginMutation.isPending && llmAuthLoginMutation.variables?.provider === provider;

            return (
              <SafetyActionForm
                id={`${provider}-llm-auth`}
                key={provider}
                title={formatMessage(t("controls.action.llmAuth.title"), {
                  provider: displayName,
                })}
                description={t("controls.action.llmAuth.description")}
                badgeLabel={provider.toUpperCase()}
                badgeTone="neutral"
                reasonLabel={formatMessage(t("controls.action.llmAuth.reason"), {
                  provider: displayName,
                })}
                reasonPlaceholder={t("controls.action.llmAuth.placeholder")}
                reviewLabel={formatMessage(t("controls.action.llmAuth.review"), {
                  provider: displayName,
                })}
                confirmLabel={formatMessage(t("controls.action.llmAuth.confirm"), {
                  provider: displayName,
                })}
                pendingLabel={t("controls.action.llmAuth.pending")}
                buttonTone="neutral"
                isPending={providerIsPending}
                isDisabled={isOperationInFlight}
                submitted={(reason) => llmAuthLoginMutation.mutate({ provider, reason })}
              />
            );
          })}
        </div>
        <LlmAuthLoginSessionPanel
          session={llmAuthLoginSessionQuery.data ?? activeLlmAuthLogin?.session ?? null}
        />
      </Panel>
    </div>
  );
}

async function refreshControlsData(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["ops"] }),
    queryClient.invalidateQueries({ queryKey: ["system-status"] }),
    queryClient.invalidateQueries({ queryKey: ["evaluation"] }),
  ]);
}

function ControlNoticePanel({ notice }: { notice: ControlNotice }) {
  return (
    <div className={`control-notice control-notice--${notice.tone}`} role={notice.tone === "critical" ? "alert" : "status"}>
      <StatusPill label={notice.tone} tone={notice.tone} />
      <div>
        <p className="control-notice__title">{notice.title}</p>
        <p className="control-notice__detail">{notice.detail}</p>
      </div>
    </div>
  );
}

function ControlsRiskStatePanel({ riskStateQuery }: { riskStateQuery: UseQueryResult<OpsRiskStateResponse, Error> }) {
  const { t, locale } = useI18n();

  if (riskStateQuery.isPending) {
    return <PanelLoading label={t("controls.loading.riskState")} />;
  }

  if (riskStateQuery.isError) {
    return <PanelError title={t("controls.error.riskState")} error={riskStateQuery.error} retried={() => void riskStateQuery.refetch()} />;
  }

  return (
    <Panel>
      <div className="panel-heading">
        <ShieldAlert size={18} aria-hidden="true" />
        <h2>{t("controls.panel.currentHaltState")}</h2>
        <StatusPill label={riskStateQuery.data.state} tone={riskStateTone(riskStateQuery.data.state)} />
        {riskStateQuery.isStale ? <StatusPill label={t("common.stale")} tone="warning" /> : <StatusPill label={t("common.fresh")} tone="positive" />}
      </div>
      <DataStrip
        items={[
          {
            label: t("controls.label.state"),
            value: riskStateQuery.data.state,
            detail: riskStateQuery.data.haltReason ?? t("controls.detail.noHaltReason"),
          },
          {
            label: t("controls.label.drawdown"),
            value: formatRatioAsPercent(riskStateQuery.data.drawdownRatio),
          },
          {
            label: t("controls.label.haltedAt"),
            value: formatDateTime(riskStateQuery.data.haltAt, locale),
          },
          {
            label: t("controls.label.resumedAt"),
            value: formatDateTime(riskStateQuery.data.resumedAt, locale),
            detail: riskStateQuery.data.resumedReason ?? t("controls.detail.noResumeReason"),
          },
        ]}
      />
    </Panel>
  );
}

function ControlsExposurePanel({ positionsQuery }: { positionsQuery: UseQueryResult<OpsPositionsResponse, Error> }) {
  const { t } = useI18n();

  if (positionsQuery.isPending) {
    return <PanelLoading label={t("controls.loading.exposureState")} />;
  }

  if (positionsQuery.isError) {
    return <PanelError title={t("controls.error.exposureState")} error={positionsQuery.error} retried={() => void positionsQuery.refetch()} />;
  }

  const hasOpenRisk = positionsQuery.data.positions.length > 0 || positionsQuery.data.openOrders.length > 0;

  return (
    <Panel>
      <div className="panel-heading">
        <AlertTriangle size={18} aria-hidden="true" />
        <h2>{t("controls.panel.openRiskCheck")}</h2>
        <StatusPill label={hasOpenRisk ? t("controls.status.openRisk") : t("controls.status.flat")} tone={hasOpenRisk ? "warning" : "positive"} />
        {positionsQuery.isStale ? <StatusPill label={t("common.stale")} tone="warning" /> : <StatusPill label={t("common.fresh")} tone="positive" />}
      </div>
      <DataStrip
        items={[
          {
            label: t("controls.label.positions"),
            value: String(positionsQuery.data.positions.length),
          },
          {
            label: t("controls.label.openOrders"),
            value: String(positionsQuery.data.openOrders.length),
          },
          {
            label: t("controls.label.btcSize"),
            value: formatBtc(sumNumbers(positionsQuery.data.positions.map((position) => position.sizeBtc))),
          },
          {
            label: t("controls.label.unrealizedPnl"),
            value: formatSignedJpy(sumNumbers(positionsQuery.data.positions.map((position) => position.unrealizedPnlJpy))),
          },
        ]}
      />
      <p className="control-panel-note">{t("controls.note.exposure")}</p>
    </Panel>
  );
}

function HaltSemantics() {
  const { t } = useI18n();

  return (
    <div className="halt-semantics" aria-label={t("controls.haltSemantics.aria")}>
      <div className="halt-semantics__item halt-semantics__item--soft">
        <StatusPill label="SOFT_HALT" tone="warning" />
        <p>{t("controls.haltSemantics.soft")}</p>
      </div>
      <div className="halt-semantics__item halt-semantics__item--hard">
        <StatusPill label="HARD_HALT" tone="critical" />
        <p>{t("controls.haltSemantics.hard")}</p>
      </div>
    </div>
  );
}

function LlmAuthLoginSessionPanel({ session }: { session: OpsLlmAuthLoginResponse | null }) {
  const { locale, t } = useI18n();

  if (!session) {
    return null;
  }

  return (
    <div className="llm-auth-session">
      <div className="llm-auth-session__heading">
        <h3>{t("controls.panel.llmAuthSession")}</h3>
        <StatusPill label={session.status} tone={llmAuthLoginStatusTone(session.status)} />
      </div>
      <DataStrip
        items={[
          {
            label: t("controls.label.provider"),
            value: providerDisplayName(session.provider),
          },
          {
            label: t("controls.label.status"),
            value: session.status,
            detail: session.detail ?? t("common.notReported"),
          },
          {
            label: t("controls.label.userCode"),
            value: session.userCode ?? t("controls.detail.authorizationPending"),
          },
          {
            label: t("controls.label.expiresAt"),
            value: formatDateTime(session.expiresAt, locale),
          },
          {
            label: t("controls.label.completedAt"),
            value: formatDateTime(session.completedAt, locale),
            detail: session.completedAt ? undefined : t("controls.detail.notCompleted"),
          },
        ]}
      />
      <div className="llm-auth-session__url">
        <span>{t("controls.label.authorizationUrl")}</span>
        {session.authorizationUrl ? (
          <a href={session.authorizationUrl} target="_blank" rel="noreferrer">
            {session.authorizationUrl}
          </a>
        ) : (
          <p>{t("controls.detail.authorizationPending")}</p>
        )}
      </div>
    </div>
  );
}

function SafetyActionForm({
  id,
  title,
  description,
  badgeLabel,
  badgeTone,
  reasonLabel,
  reasonPlaceholder,
  reviewLabel,
  confirmLabel,
  pendingLabel,
  buttonTone,
  isPending,
  isDisabled,
  submitted,
}: SafetyActionFormProps) {
  const { t } = useI18n();
  const [reason, setReason] = useState("");
  const [validationError, setValidationError] = useState<string | null>(null);
  const [confirmationReason, setConfirmationReason] = useState<string | null>(null);
  const inputId = `${id}-reason`;
  const errorId = `${id}-reason-error`;
  const reasonChanged = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setReason(event.target.value);
    setValidationError(null);
    setConfirmationReason(null);
  };
  const reviewed = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    if (isDisabled) {
      return;
    }

    const trimmedReason = reason.trim();

    if (!trimmedReason) {
      setValidationError(t("controls.validation.reasonRequired"));
      setConfirmationReason(null);

      return;
    }

    setValidationError(null);
    setConfirmationReason(trimmedReason);
  };
  const confirmed = () => {
    if (!confirmationReason || isDisabled) {
      return;
    }

    submitted(confirmationReason);
    setConfirmationReason(null);
  };

  return (
    <form className={`control-action control-action--${buttonTone}`} onSubmit={reviewed}>
      <div className="control-action__header">
        <div>
          <h3>{title}</h3>
          <p>{description}</p>
        </div>
        <StatusPill label={badgeLabel} tone={badgeTone} />
      </div>

      <label className="control-action__label" htmlFor={inputId}>
        {reasonLabel}
      </label>
      <textarea
        id={inputId}
        className="control-action__textarea"
        value={reason}
        placeholder={reasonPlaceholder}
        rows={4}
        aria-describedby={validationError ? errorId : undefined}
        onChange={reasonChanged}
        disabled={isDisabled}
      />
      {validationError ? (
        <p className="control-action__error" id={errorId} role="alert">
          {validationError}
        </p>
      ) : null}

      <div className="control-action__buttons">
        <button className="icon-text-button" type="submit" disabled={isDisabled}>
          {isPending ? pendingLabel : reviewLabel}
        </button>
      </div>

      {confirmationReason ? (
        <div className="confirmation-step" role="group" aria-label={`${confirmLabel} ${t("controls.confirm.confirmation")}`}>
          <p className="confirmation-step__title">{t("controls.confirm.beforeSending")}</p>
          <p className="confirmation-step__reason">{confirmationReason}</p>
          <div className="confirmation-step__buttons">
            <button
              className={confirmButtonClassName(buttonTone)}
              type="button"
              onClick={confirmed}
              disabled={isDisabled}
            >
              {isPending ? pendingLabel : confirmLabel}
            </button>
            <button
              className="icon-text-button"
              type="button"
              onClick={() => setConfirmationReason(null)}
              disabled={isDisabled}
            >
              {t("controls.confirm.editReason")}
            </button>
          </div>
        </div>
      ) : null}
    </form>
  );
}

function PanelLoading({ label }: { label: string }) {
  return (
    <Panel>
      <div className="loading-row" role="status">
        <span className="loading-dot" aria-hidden="true" />
        <span>{label}</span>
      </div>
    </Panel>
  );
}

function PanelError({ title, error, retried }: { title: string; error: unknown; retried: () => void }) {
  const { t } = useI18n();

  return (
    <Panel>
      <EmptyState
        title={title}
        description={describeControlError(error, t)}
        action={
          <button className="icon-text-button" type="button" onClick={retried}>
            <RefreshCw size={16} aria-hidden="true" />
            {t("common.retry")}
          </button>
        }
      />
    </Panel>
  );
}

function confirmButtonClassName(tone: ActionButtonTone): string {
  return ["icon-text-button", `icon-text-button--${tone}`].join(" ");
}

function riskStateTone(state: string): StatusTone {
  if (state === "RUNNING") {
    return "positive";
  }

  if (state === "SOFT_HALT") {
    return "warning";
  }

  if (state === "HARD_HALT") {
    return "critical";
  }

  return "neutral";
}

function llmAuthLoginStatusTone(status: string): StatusTone {
  switch (status) {
    case "succeeded":
      return "positive";
    case "failed":
    case "timed_out":
      return "critical";
    case "running":
      return "warning";
    default:
      return "neutral";
  }
}

function providerDisplayName(provider: string): string {
  switch (provider) {
    case "claude":
      return "Claude Code";
    case "codex":
      return "Codex";
    default:
      return provider;
  }
}

function sumNumbers(values: string[]): string | null {
  let total = 0;

  for (const value of values) {
    const parsedValue = Number(value);

    if (!Number.isFinite(parsedValue)) {
      return null;
    }

    total += parsedValue;
  }

  return String(total);
}

function describeControlError(error: unknown, t: Translate): string {
  if (!(error instanceof ApiClientError)) {
    return describeError(error);
  }

  const apiMessage = extractApiErrorMessage(error.responseText);

  if (error.path === "/ops/trigger" && error.status === 409) {
    return formatMessage(t("controls.error.manualTriggerRefused"), {
      reason: triggerRefusalDescription(apiMessage, t),
    });
  }

  if (error.path.includes("/ops/llm-auth/") && error.status === 409) {
    return apiMessage === "login already in progress"
      ? t("controls.error.llmAuthInProgress")
      : `${apiMessage} (HTTP ${error.status})`;
  }

  if (error.path === "/ops/halt" && error.status === 409) {
    return formatMessage(t("controls.error.haltRefused"), {
      reason: haltRefusalDescription(apiMessage, t),
    });
  }

  if (apiMessage) {
    return `${apiMessage} (HTTP ${error.status})`;
  }

  return error.message;
}

function extractApiErrorMessage(responseText: string): string {
  if (!responseText.trim()) {
    return "";
  }

  try {
    const parsedBody = JSON.parse(responseText) as { message?: unknown };

    return typeof parsedBody.message === "string" ? parsedBody.message : responseText;
  } catch {
    return responseText;
  }
}

function triggerRefusalDescription(reason: string, t: Translate): string {
  switch (reason) {
    case "hard_halt":
      return t("controls.error.hardHaltActive");
    case "soft_halt_flat":
      return t("controls.error.softHaltFlat");
    case "concurrent_invocation":
      return t("controls.error.concurrentInvocation");
    case "max_invocations_per_hour_exceeded":
      return t("controls.error.hourlyCap");
    case "max_invocations_per_day_exceeded":
      return t("controls.error.dailyCap");
    default:
      return reason || t("controls.error.noRefusalReason");
  }
}

function haltRefusalDescription(reason: string, t: Translate): string {
  if (reason === "SOFT_HALT cannot downgrade HARD_HALT.") {
    return t("controls.error.cannotDowngradeHardHalt");
  }

  return reason || t("controls.error.noRefusalReason");
}

function formatMessage(template: string, replacements: Record<string, string>): string {
  return Object.entries(replacements).reduce(
    (message, [key, value]) => message.replace(`{${key}}`, value),
    template,
  );
}
