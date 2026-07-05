const jpyFormatter = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "JPY",
  maximumFractionDigits: 0,
});

const signedJpyFormatter = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "JPY",
  maximumFractionDigits: 0,
  signDisplay: "exceptZero",
});

const btcFormatter = new Intl.NumberFormat("en-US", {
  maximumFractionDigits: 8,
  minimumFractionDigits: 0,
});

const percentFormatter = new Intl.NumberFormat("en-US", {
  maximumFractionDigits: 2,
  minimumFractionDigits: 0,
  style: "percent",
});

const decimalFormatter = new Intl.NumberFormat("en-US", {
  maximumFractionDigits: 4,
  minimumFractionDigits: 0,
});

const integerFormatter = new Intl.NumberFormat("en-US", {
  maximumFractionDigits: 0,
});

const usdFormatter = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  maximumFractionDigits: 4,
  minimumFractionDigits: 0,
});

export function formatJpy(value: string | null | undefined): string {
  const number = parseNumericValue(value);

  return number === null ? "not reported" : jpyFormatter.format(number);
}

export function formatSignedJpy(value: string | null | undefined): string {
  const number = parseNumericValue(value);

  return number === null ? "not reported" : signedJpyFormatter.format(number);
}

export function formatBtc(value: string | null | undefined): string {
  const number = parseNumericValue(value);

  return number === null ? "not reported" : `${btcFormatter.format(number)} BTC`;
}

export function formatRatioAsPercent(value: string | null | undefined): string {
  const number = parseNumericValue(value);

  return number === null ? "not reported" : percentFormatter.format(number);
}

export function formatDecimal(value: string | null | undefined): string {
  const number = parseNumericValue(value);

  return number === null ? "not reported" : decimalFormatter.format(number);
}

export function formatInteger(value: number | null | undefined): string {
  return value === null || value === undefined ? "not reported" : integerFormatter.format(value);
}

export function formatUsd(value: string | null | undefined): string {
  const number = parseNumericValue(value);

  return number === null ? "not reported" : usdFormatter.format(number);
}

function parseNumericValue(value: string | null | undefined): number | null {
  if (value === null || value === undefined || value.trim() === "") {
    return null;
  }

  const number = Number(value);

  return Number.isFinite(number) ? number : null;
}
