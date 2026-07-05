export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "not reported";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
}

export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return "not reported";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleTimeString();
}

export function describeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }

  return "Unknown API error";
}
