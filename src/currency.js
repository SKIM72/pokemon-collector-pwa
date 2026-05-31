const RATES_KEY = "pokebinder.currencyRates";
const FALLBACK_RATES = {
  USD: 1,
  JPY: 157,
  KRW: 1380,
  EUR: 0.92,
};

export const DISPLAY_CURRENCIES = ["JPY", "KRW", "USD"];

export async function loadCurrencyRates() {
  const cached = readCachedRates();
  if (cached && Date.now() - cached.fetchedAt < 1000 * 60 * 60 * 8) {
    return cached;
  }

  try {
    const response = await fetch("https://open.er-api.com/v6/latest/USD");
    if (!response.ok) throw new Error("rate fetch failed");

    const payload = await response.json();
    const rates = DISPLAY_CURRENCIES.reduce(
      (acc, currency) => ({
        ...acc,
        [currency]: Number(payload.rates?.[currency] || FALLBACK_RATES[currency]),
      }),
      { USD: 1, EUR: Number(payload.rates?.EUR || FALLBACK_RATES.EUR) },
    );

    const next = {
      base: "USD",
      fetchedAt: Date.now(),
      rates,
    };

    localStorage.setItem(RATES_KEY, JSON.stringify(next));
    return next;
  } catch {
    return {
      base: "USD",
      fetchedAt: Date.now(),
      rates: FALLBACK_RATES,
      fallback: true,
    };
  }
}

export function convertMoney(value, fromCurrency, toCurrency, rates) {
  const amount = Number(value || 0);
  const from = fromCurrency || "USD";
  const to = toCurrency || "USD";

  if (!amount || from === to) return amount;

  const fromRate = Number(rates?.[from] || FALLBACK_RATES[from] || 1);
  const toRate = Number(rates?.[to] || FALLBACK_RATES[to] || 1);
  return (amount / fromRate) * toRate;
}

export function formatMoney(value, currency) {
  const fractionDigits = ["JPY", "KRW"].includes(currency) ? 0 : 2;
  return new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency,
    maximumFractionDigits: fractionDigits,
  }).format(Number(value || 0));
}

function readCachedRates() {
  try {
    return JSON.parse(localStorage.getItem(RATES_KEY) || "null");
  } catch {
    return null;
  }
}
