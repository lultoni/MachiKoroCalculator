import { createContext, useContext, useState, useCallback, type ReactNode } from 'react';
import de from './de';
import en from './en';

type Locale = 'de' | 'en';

const locales: Record<Locale, Record<string, string>> = { de, en };

interface LocaleContextValue {
  locale: Locale;
  setLocale: (l: Locale) => void;
  t: (key: string, params?: Record<string, string | number>) => string;
}

const LocaleContext = createContext<LocaleContextValue>({
  locale: 'de',
  setLocale: () => {},
  t: (key) => key,
});

export function LocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocale] = useState<Locale>(
    () => (localStorage.getItem('machi-locale') as Locale) || 'de'
  );

  const handleSetLocale = useCallback((l: Locale) => {
    setLocale(l);
    localStorage.setItem('machi-locale', l);
  }, []);

  const t = useCallback(
    (key: string, params?: Record<string, string | number>) => {
      let str = locales[locale][key] ?? key;
      if (params) {
        for (const [k, v] of Object.entries(params)) {
          str = str.replace(`{${k}}`, String(v));
        }
      }
      return str;
    },
    [locale]
  );

  return (
    <LocaleContext.Provider value={{ locale, setLocale: handleSetLocale, t }}>
      {children}
    </LocaleContext.Provider>
  );
}

export function useLocale() {
  return useContext(LocaleContext);
}
