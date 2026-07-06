const TIME_ZONE = "Asia/Tokyo";
const TIME_ZONE_LABEL = "JST";

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "not reported";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  const formatted = date.toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZone: TIME_ZONE,
  });

  return `${formatted} ${TIME_ZONE_LABEL}`;
}

export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return "not reported";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return formatJstTime(date);
}

export function formatJstClock(date: Date): string {
  return formatJstTime(date);
}

export function describeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }

  return "Unknown API error";
}

function formatJstTime(date: Date): string {
  const formatted = date.toLocaleTimeString("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZone: TIME_ZONE,
  });

  return `${formatted} ${TIME_ZONE_LABEL}`;
}
