import { useEffect, useMemo, useState, type ReactNode } from "react";
import { I18nContext } from "./context";
import { isLocale, LOCALE_STORAGE_KEY, messages, type Locale, type MessageKey } from "./messages";

type I18nProviderProps = {
  children: ReactNode;
};

export function I18nProvider({ children }: I18nProviderProps) {
  const [locale, setLocale] = useState<Locale>(readStoredLocale);

  useEffect(() => {
    document.documentElement.lang = locale;
    try {
      window.localStorage.setItem(LOCALE_STORAGE_KEY, locale);
    } catch {
      // Some browser privacy modes disable storage writes. Keep the in-memory locale usable.
    }
  }, [locale]);

  const value = useMemo(
    () => ({
      locale,
      setLocale,
      t: (key: MessageKey) => messages[locale][key],
    }),
    [locale],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

function readStoredLocale(): Locale {
  if (typeof window === "undefined") {
    return "en";
  }

  const storedLocale = readLocalStorageLocale();

  return isLocale(storedLocale) ? storedLocale : "en";
}

function readLocalStorageLocale(): string | null {
  try {
    return window.localStorage.getItem(LOCALE_STORAGE_KEY);
  } catch {
    return null;
  }
}
