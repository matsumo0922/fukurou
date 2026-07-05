export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "not reported";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZoneName: "short",
    timeZone: "UTC",
  });
}

export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return "not reported";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return formatUtcTime(date);
}

export function formatUtcClock(date: Date): string {
  return formatUtcTime(date);
}

export function describeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }

  return "Unknown API error";
}

function formatUtcTime(date: Date): string {
  return date.toLocaleTimeString("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZone: "UTC",
    timeZoneName: "short",
  });
}
