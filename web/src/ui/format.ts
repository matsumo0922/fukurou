import type { Locale } from "../i18n/messages";

const JST_TIME_ZONE = "Asia/Tokyo";
const JST_LABEL = "JST";

type DateTimeParts = {
  year: string;
  month: string;
  day: string;
  hour: string;
  minute: string;
  second: string;
};

export function formatDateTime(value: string | null | undefined, locale: Locale = "en"): string {
  if (!value) {
    return missingValueLabel(locale);
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  const parts = dateTimeParts(date, locale);

  if (locale === "ja") {
    return `${parts.year}/${parts.month}/${parts.day} ${parts.hour}:${parts.minute}:${parts.second} ${JST_LABEL}`;
  }

  return `${parts.month} ${parts.day}, ${parts.year} ${parts.hour}:${parts.minute}:${parts.second} ${JST_LABEL}`;
}

export function formatTime(value: string | null | undefined, locale: Locale = "en"): string {
  if (!value) {
    return missingValueLabel(locale);
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return formatClock(date, locale);
}

export function formatClock(date: Date, locale: Locale = "en"): string {
  const parts = dateTimeParts(date, locale);

  return `${parts.hour}:${parts.minute}:${parts.second} ${JST_LABEL}`;
}

export function describeError(error: unknown, locale: Locale = "en"): string {
  if (error instanceof Error) {
    return error.message;
  }

  return locale === "ja" ? "不明な API エラー" : "Unknown API error";
}

function dateTimeParts(date: Date, locale: Locale): DateTimeParts {
  const formatter = new Intl.DateTimeFormat(locale === "ja" ? "ja-JP" : "en-US", {
    year: "numeric",
    month: locale === "ja" ? "2-digit" : "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    hourCycle: "h23",
    timeZone: JST_TIME_ZONE,
  });

  const parts = Object.fromEntries(formatter.formatToParts(date).map((part) => [part.type, part.value]));

  return {
    year: parts.year,
    month: parts.month,
    day: parts.day,
    hour: parts.hour,
    minute: parts.minute,
    second: parts.second,
  };
}

function missingValueLabel(locale: Locale): string {
  return locale === "ja" ? "未報告" : "not reported";
}
