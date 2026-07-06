import { createContext } from "react";
import type { Locale, MessageKey } from "./messages";

export type I18nContextValue = {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (key: MessageKey) => string;
};

export const I18nContext = createContext<I18nContextValue | null>(null);
