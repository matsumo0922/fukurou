import { useMutation, useQuery, useQueryClient, type QueryClient, type UseQueryResult } from "@tanstack/react-query";
import { useState, type ChangeEvent, type FormEvent } from "react";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import AlertTriangle from "lucide-react/dist/esm/icons/alert-triangle.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import ShieldAlert from "lucide-react/dist/esm/icons/shield-alert.mjs";
import { ApiClientError } from "../api/client";
import {
  opsPositionsQuery,
  opsRiskStateQuery,
  requestOpsHalt,
  requestOpsResume,
  requestOpsTrigger,
  type OpsPositionsResponse,
  type OpsRiskStateResponse,
} from "../api/ops";
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

type ActionButtonTone = "neutral" | "warning" | "critical";

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
  submitted: (reason: string) => void;
};

const REQUIRED_REASON_MESSAGE = "Reason is required before this operation can be reviewed.";

export function ControlsPage() {
  const queryClient = useQueryClient();
  const riskStateQuery = useQuery(opsRiskStateQuery);
  const positionsQuery = useQuery(opsPositionsQuery);
  const [notice, setNotice] = useState<ControlNotice | null>(null);

  const refreshAfterSuccess = () => {
    void refreshControlsData(queryClient);
  };
  const softHaltMutation = useMutation({
    mutationFn: (reason: string) => requestOpsHalt("SOFT", reason),
    onSuccess: (riskState) => {
      setNotice({
        tone: "warning",
        title: "SOFT_HALT set",
        detail: `Risk state is now ${riskState.state}. Risk, activity, and system views are refreshing.`,
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: "SOFT_HALT failed",
        detail: describeControlError(error),
      });
    },
  });
  const hardHaltMutation = useMutation({
    mutationFn: (reason: string) => requestOpsHalt("HARD", reason),
    onSuccess: (riskState) => {
      setNotice({
        tone: "critical",
        title: "HARD_HALT set",
        detail: `Risk state is now ${riskState.state}. Risk, activity, and system views are refreshing.`,
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: "HARD_HALT failed",
        detail: describeControlError(error),
      });
    },
  });
  const resumeMutation = useMutation({
    mutationFn: requestOpsResume,
    onSuccess: (riskState) => {
      setNotice({
        tone: "positive",
        title: "Resume requested",
        detail: `Risk state is now ${riskState.state}. Risk, activity, and system views are refreshing.`,
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: "Resume failed",
        detail: describeControlError(error),
      });
    },
  });
  const triggerMutation = useMutation({
    mutationFn: requestOpsTrigger,
    onSuccess: (trigger) => {
      setNotice({
        tone: "positive",
        title: "Manual trigger accepted",
        detail: `Invocation ${trigger.invocationId} (${trigger.triggerKind}) was queued. Activity and system views are refreshing.`,
      });
      refreshAfterSuccess();
    },
    onError: (error) => {
      setNotice({
        tone: "critical",
        title: "Manual trigger failed",
        detail: describeControlError(error),
      });
    },
  });
  const isRefreshing = riskStateQuery.isFetching || positionsQuery.isFetching;
  const refreshed = () => {
    void riskStateQuery.refetch();
    void positionsQuery.refetch();
  };

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="Operate"
        title="Controls"
        description="Reasoned operator actions for halt, resume, and one-shot manual LLM launch."
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={refreshed}
            disabled={isRefreshing}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {isRefreshing ? "Refreshing" : "Refresh"}
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
          <h2>Halt controls</h2>
        </div>
        <HaltSemantics />
        <div className="control-action-grid control-action-grid--two">
          <SafetyActionForm
            id="soft-halt"
            title="Set SOFT_HALT"
            description="Reject new entry decisions while allowing exits and protective operations to continue."
            badgeLabel="SOFT_HALT"
            badgeTone="warning"
            reasonLabel="SOFT_HALT reason"
            reasonPlaceholder="e.g. Pausing new entries while reviewing market regime shift"
            reviewLabel="Review SOFT_HALT"
            confirmLabel="Confirm SOFT_HALT"
            pendingLabel="Setting SOFT_HALT"
            buttonTone="warning"
            isPending={softHaltMutation.isPending}
            submitted={(reason) => softHaltMutation.mutate(reason)}
          />
          <SafetyActionForm
            id="hard-halt"
            title="Set HARD_HALT"
            description="Full stop for trading operations. Use only when the bot must not continue activity."
            badgeLabel="HARD_HALT"
            badgeTone="critical"
            reasonLabel="HARD_HALT reason"
            reasonPlaceholder="e.g. Emergency stop after safety breach investigation"
            reviewLabel="Review HARD_HALT"
            confirmLabel="Confirm HARD_HALT"
            pendingLabel="Setting HARD_HALT"
            buttonTone="critical"
            isPending={hardHaltMutation.isPending}
            submitted={(reason) => hardHaltMutation.mutate(reason)}
          />
        </div>
      </Panel>

      <div className="page-grid page-grid--two">
        <Panel>
          <div className="panel-heading">
            <RefreshCw size={18} aria-hidden="true" />
            <h2>Resume control</h2>
          </div>
          <SafetyActionForm
            id="resume"
            title="Request resume"
            description="Move a halted bot back to RUNNING only after the operator has checked the current state."
            badgeLabel="RUNNING"
            badgeTone="positive"
            reasonLabel="Resume reason"
            reasonPlaceholder="e.g. Safety review completed and account state matches expectations"
            reviewLabel="Review resume request"
            confirmLabel="Confirm resume request"
            pendingLabel="Requesting resume"
            buttonTone="neutral"
            isPending={resumeMutation.isPending}
            submitted={(reason) => resumeMutation.mutate(reason)}
          />
        </Panel>

        <Panel>
          <div className="panel-heading">
            <Activity size={18} aria-hidden="true" />
            <h2>Manual trigger</h2>
          </div>
          <SafetyActionForm
            id="manual-trigger"
            title="Run one-shot LLM"
            description="Request a single MANUAL LLM launch. The backend may refuse it for halt state, concurrency, or invocation limits."
            badgeLabel="MANUAL"
            badgeTone="neutral"
            reasonLabel="Manual trigger reason"
            reasonPlaceholder="e.g. Operator requested one evaluation after deployment smoke check"
            reviewLabel="Review manual trigger"
            confirmLabel="Confirm manual trigger"
            pendingLabel="Requesting trigger"
            buttonTone="neutral"
            isPending={triggerMutation.isPending}
            submitted={(reason) => triggerMutation.mutate(reason)}
          />
        </Panel>
      </div>
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
  if (riskStateQuery.isPending) {
    return <PanelLoading label="Loading risk state" />;
  }

  if (riskStateQuery.isError) {
    return <PanelError title="Risk state unavailable" error={riskStateQuery.error} retried={() => void riskStateQuery.refetch()} />;
  }

  return (
    <Panel>
      <div className="panel-heading">
        <ShieldAlert size={18} aria-hidden="true" />
        <h2>Current halt state</h2>
        <StatusPill label={riskStateQuery.data.state} tone={riskStateTone(riskStateQuery.data.state)} />
        {riskStateQuery.isStale ? <StatusPill label="stale" tone="warning" /> : <StatusPill label="fresh" tone="positive" />}
      </div>
      <DataStrip
        items={[
          {
            label: "state",
            value: riskStateQuery.data.state,
            detail: riskStateQuery.data.haltReason ?? "no halt reason",
          },
          {
            label: "drawdown",
            value: formatRatioAsPercent(riskStateQuery.data.drawdownRatio),
          },
          {
            label: "halted at",
            value: formatDateTime(riskStateQuery.data.haltAt),
          },
          {
            label: "resumed at",
            value: formatDateTime(riskStateQuery.data.resumedAt),
            detail: riskStateQuery.data.resumedReason ?? "no resume reason",
          },
        ]}
      />
    </Panel>
  );
}

function ControlsExposurePanel({ positionsQuery }: { positionsQuery: UseQueryResult<OpsPositionsResponse, Error> }) {
  if (positionsQuery.isPending) {
    return <PanelLoading label="Loading exposure state" />;
  }

  if (positionsQuery.isError) {
    return <PanelError title="Exposure state unavailable" error={positionsQuery.error} retried={() => void positionsQuery.refetch()} />;
  }

  const hasOpenRisk = positionsQuery.data.positions.length > 0 || positionsQuery.data.openOrders.length > 0;

  return (
    <Panel>
      <div className="panel-heading">
        <AlertTriangle size={18} aria-hidden="true" />
        <h2>Open risk check</h2>
        <StatusPill label={hasOpenRisk ? "open risk" : "flat"} tone={hasOpenRisk ? "warning" : "positive"} />
        {positionsQuery.isStale ? <StatusPill label="stale" tone="warning" /> : <StatusPill label="fresh" tone="positive" />}
      </div>
      <DataStrip
        items={[
          {
            label: "positions",
            value: String(positionsQuery.data.positions.length),
          },
          {
            label: "open orders",
            value: String(positionsQuery.data.openOrders.length),
          },
          {
            label: "BTC size",
            value: formatBtc(sumNumbers(positionsQuery.data.positions.map((position) => position.sizeBtc))),
          },
          {
            label: "unrealized PnL",
            value: formatSignedJpy(sumNumbers(positionsQuery.data.positions.map((position) => position.unrealizedPnlJpy))),
          },
        ]}
      />
      <p className="control-panel-note">
        SOFT_HALT blocks a manual trigger when the account is flat. HARD_HALT blocks manual launch regardless of exposure.
      </p>
    </Panel>
  );
}

function HaltSemantics() {
  return (
    <div className="halt-semantics" aria-label="SOFT_HALT and HARD_HALT semantics">
      <div className="halt-semantics__item halt-semantics__item--soft">
        <StatusPill label="SOFT_HALT" tone="warning" />
        <p>New entry decisions are rejected. Exits and protective operations keep passing.</p>
      </div>
      <div className="halt-semantics__item halt-semantics__item--hard">
        <StatusPill label="HARD_HALT" tone="critical" />
        <p>Full stop. Trading operations stay blocked until an operator resumes with a reason.</p>
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
  submitted,
}: SafetyActionFormProps) {
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

    const trimmedReason = reason.trim();

    if (!trimmedReason) {
      setValidationError(REQUIRED_REASON_MESSAGE);
      setConfirmationReason(null);

      return;
    }

    setValidationError(null);
    setConfirmationReason(trimmedReason);
  };
  const confirmed = () => {
    if (!confirmationReason) {
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
        disabled={isPending}
      />
      {validationError ? (
        <p className="control-action__error" id={errorId} role="alert">
          {validationError}
        </p>
      ) : null}

      <div className="control-action__buttons">
        <button className="icon-text-button" type="submit" disabled={isPending}>
          {isPending ? pendingLabel : reviewLabel}
        </button>
      </div>

      {confirmationReason ? (
        <div className="confirmation-step" role="group" aria-label={`${confirmLabel} confirmation`}>
          <p className="confirmation-step__title">Confirm before sending</p>
          <p className="confirmation-step__reason">{confirmationReason}</p>
          <div className="confirmation-step__buttons">
            <button
              className={confirmButtonClassName(buttonTone)}
              type="button"
              onClick={confirmed}
              disabled={isPending}
            >
              {isPending ? pendingLabel : confirmLabel}
            </button>
            <button
              className="icon-text-button"
              type="button"
              onClick={() => setConfirmationReason(null)}
              disabled={isPending}
            >
              Edit reason
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
  return (
    <Panel>
      <EmptyState
        title={title}
        description={describeControlError(error)}
        action={
          <button className="icon-text-button" type="button" onClick={retried}>
            <RefreshCw size={16} aria-hidden="true" />
            Retry
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

function describeControlError(error: unknown): string {
  if (!(error instanceof ApiClientError)) {
    return describeError(error);
  }

  const apiMessage = extractApiErrorMessage(error.responseText);

  if (error.path === "/ops/trigger" && error.status === 409) {
    return `Manual trigger was refused: ${triggerRefusalDescription(apiMessage)}`;
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

function triggerRefusalDescription(reason: string): string {
  switch (reason) {
    case "hard_halt":
      return "HARD_HALT is active, so manual launch stays blocked until an operator resumes.";
    case "soft_halt_flat":
      return "SOFT_HALT is active and the account is flat, so new entry checks stay blocked.";
    case "concurrent_invocation":
      return "another LLM invocation is already running; wait for it to finish before trying again.";
    case "max_invocations_per_hour_exceeded":
      return "the hourly LLM invocation cap has already been reached.";
    case "max_invocations_per_day_exceeded":
      return "the daily LLM invocation cap has already been reached.";
    default:
      return reason || "the backend did not provide a refusal reason.";
  }
}
