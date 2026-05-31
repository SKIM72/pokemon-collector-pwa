import { expandQueryAliases, getAliasLocalizations } from "./aliases.js";

const POKEMON_TCG_API = "https://api.pokemontcg.io/v2/cards";
const TCGDEX_API = "https://api.tcgdex.net/v2";

const LANGUAGES = [
  { id: "ja", label: "일본어", defaultCurrency: "JPY" },
  { id: "ko", label: "한국어", defaultCurrency: "KRW" },
  { id: "en", label: "영어", defaultCurrency: "USD" },
];

const PROVIDERS = {
  all: {
    source: "all",
    language: "all",
    label: "전체 언어",
    defaultCurrency: "JPY",
  },
  "tcgdex-ja": {
    source: "tcgdex",
    language: "ja",
    label: "TCGdex 일본어",
    defaultCurrency: "JPY",
  },
  "tcgdex-ko": {
    source: "tcgdex",
    language: "ko",
    label: "TCGdex 한국어",
    defaultCurrency: "KRW",
  },
  "tcgdex-en": {
    source: "tcgdex",
    language: "en",
    label: "TCGdex 영어",
    defaultCurrency: "USD",
  },
  "pokemontcg-en": {
    source: "pokemontcg",
    language: "en",
    label: "Pokemon TCG 가격",
    defaultCurrency: "USD",
  },
};

export function getProvider(providerId) {
  return PROVIDERS[providerId] || PROVIDERS.all;
}

export async function searchCards(providerId, query) {
  const provider = getProvider(providerId);
  const variants = expandQueryAliases(query);
  if (!variants.length) return [];

  if (provider.source === "all") {
    return searchAllLanguages(variants);
  }

  if (provider.source === "pokemontcg") {
    const englishVariants = variants.filter((variant) => hasLatin(variant));
    const queries = englishVariants.length ? englishVariants : variants;
    const results = await Promise.all(queries.slice(0, 3).map((variant) => searchPokemonTcg(variant, provider)));
    return dedupeCards(results.flat()).slice(0, 36);
  }

  return searchTcgDexVariants(variants, provider.language);
}

export async function hydrateCard(result) {
  if (result.source === "pokemontcg") {
    return withPriceHistorySeed(result);
  }

  const detailed = await fetchTcgDexCard(result.id, result.language);
  const card = detailed || result;
  const localizations = await fetchLocalizations(card.id);
  const normalized = normalizeTcgDexCard(card.raw || card, card.language || result.language, true, localizations);
  const price = await lookupMarketPrice(normalized);

  return withPriceHistorySeed({
    ...normalized,
    ...price,
  });
}

export async function refreshCardPrice(card) {
  if (card.source === "pokemontcg") {
    const response = await fetch(`${POKEMON_TCG_API}/${card.id}`);
    if (!response.ok) return noPrice("pokemon-tcg-unavailable");

    const payload = await response.json();
    const normalized = normalizePokemonTcgCard(payload.data, getProvider("pokemontcg-en"));
    return pickPricePatch(normalized);
  }

  const localizations = card.localizedNames?.en ? card.localizedNames : await fetchLocalizations(card.id);
  return lookupMarketPrice({
    ...card,
    localizedNames: localizations,
    englishName: localizations.en || card.englishName,
  });
}

async function searchAllLanguages(variants) {
  const languageSearches = LANGUAGES.map((language) =>
    searchTcgDexVariants(variants, language.id).catch(() => []),
  );
  const seedResults = dedupeCards((await Promise.all(languageSearches)).flat());
  const seedIds = [...new Set(seedResults.map((card) => card.id))].slice(0, 12);

  const translatedResults = await Promise.all(
    seedIds.flatMap((id) =>
      LANGUAGES.map((language) => fetchTcgDexCard(id, language.id).catch(() => null)),
    ),
  );

  const englishQueries = [
    ...variants.filter((variant) => hasLatin(variant)),
    ...translatedResults
      .filter((card) => card?.language === "en")
      .map((card) => card.name)
      .filter(Boolean),
  ];

  const pokemonResults = await Promise.all(
    [...new Set(englishQueries)].slice(0, 4).map((variant) =>
      searchPokemonTcg(variant, getProvider("pokemontcg-en")).catch(() => []),
    ),
  );

  return rankResults(dedupeCards([...seedResults, ...translatedResults.filter(Boolean), ...pokemonResults.flat()])).slice(
    0,
    72,
  );
}

async function searchTcgDexVariants(variants, language) {
  const searches = variants.slice(0, 5).map((variant) => searchTcgDex(variant, language));
  return rankResults(dedupeCards((await Promise.all(searches)).flat())).slice(0, 36);
}

async function searchTcgDex(query, language) {
  const url = new URL(`${TCGDEX_API}/${language}/cards`);
  const number = extractLocalNumber(query);

  if (number) {
    url.searchParams.set("localId", number);
  } else {
    url.searchParams.set("name", query.trim());
  }

  url.searchParams.set("pagination:page", "1");
  url.searchParams.set("pagination:itemsPerPage", "24");

  const response = await fetch(url);
  if (!response.ok) throw new Error("TCGdex 검색에 실패했습니다.");

  const data = await response.json();
  return data.map((card) => normalizeTcgDexCard(card, language, false));
}

async function fetchTcgDexCard(id, language) {
  const response = await fetch(`${TCGDEX_API}/${language}/cards/${id}`);
  if (!response.ok) return null;

  const card = await response.json();
  return normalizeTcgDexCard(card, language, true);
}

async function fetchLocalizations(id) {
  const entries = await Promise.all(
    LANGUAGES.map(async (language) => {
      const card = await fetchTcgDexCard(id, language.id).catch(() => null);
      return [language.id, card?.name || ""];
    }),
  );

  return Object.fromEntries(entries.filter(([, name]) => name));
}

async function searchPokemonTcg(query, provider) {
  const url = new URL(POKEMON_TCG_API);
  const number = extractLocalNumber(query);
  const clauses = [`name:${pokemonQueryValue(query)}*`];

  if (number) clauses.push(`number:${pokemonQueryValue(number)}`);

  url.searchParams.set("q", clauses.join(" OR "));
  url.searchParams.set("pageSize", "24");
  url.searchParams.set("orderBy", "-set.releaseDate");

  const response = await fetch(url);
  if (!response.ok) throw new Error("Pokemon TCG API 검색에 실패했습니다.");

  const payload = await response.json();
  return payload.data.map((card) => normalizePokemonTcgCard(card, provider));
}

async function lookupMarketPrice(card) {
  const englishName = card.englishName || card.localizedNames?.en || (hasLatin(card.name) ? card.name : "");
  if (!englishName) return noPrice("no-english-name");

  const candidates = await searchPokemonTcg(englishName, getProvider("pokemontcg-en")).catch(() => []);
  const priced = candidates
    .map((candidate) => ({ candidate, price: pickPokemonTcgPrice(candidate.raw) }))
    .filter(({ price }) => Number(price.value || 0) > 0)
    .sort((a, b) => scorePriceCandidate(b.candidate, card) - scorePriceCandidate(a.candidate, card));

  if (!priced.length) return noPrice("no-market-price");

  const { candidate, price } = priced[0];
  return {
    marketPrice: price.value,
    currency: price.currency,
    priceSource: price.source,
    priceFinish: price.finish,
    marketReferenceId: candidate.id,
    marketReferenceName: candidate.name,
    updatedAtMarket: candidate.updatedAtMarket,
  };
}

function normalizeTcgDexCard(card, language, detailed, localizations = {}) {
  const image = toTcgDexImage(card.image, detailed ? "high" : "low");
  const set = card.set || {};
  const currency = language === "ko" ? "KRW" : language === "en" ? "USD" : "JPY";
  const aliasLocalizations = getAliasLocalizations(card.name);
  const names = {
    ...aliasLocalizations,
    ...localizations,
    [language]: card.name || localizations[language] || aliasLocalizations[language] || "",
  };

  return {
    uid: `tcgdex-${language}:${card.id}`,
    id: card.id,
    source: "tcgdex",
    providerLabel: `TCGdex ${language.toUpperCase()}`,
    language,
    name: card.name || "Unknown",
    localizedNames: names,
    englishName: names.en || (language === "en" ? card.name : ""),
    setId: set.id || "",
    setName: set.name || "",
    number: card.localId || "",
    rarity: card.rarity || "",
    image,
    imageHigh: toTcgDexImage(card.image, "high"),
    quantity: 1,
    condition: "NM",
    finish: defaultFinish(card.variants),
    marketPrice: 0,
    currency,
    priceSource: "manual",
    raw: card,
  };
}

function normalizePokemonTcgCard(card, provider) {
  const price = pickPokemonTcgPrice(card);

  return {
    uid: `pokemontcg:${card.id}`,
    id: card.id,
    source: "pokemontcg",
    providerLabel: provider.label,
    language: provider.language,
    name: card.name || "Unknown",
    localizedNames: { en: card.name || "" },
    englishName: card.name || "",
    setId: card.set?.id || "",
    setName: card.set?.name || "",
    number: card.number || "",
    rarity: card.rarity || "",
    image: card.images?.small || "",
    imageHigh: card.images?.large || card.images?.small || "",
    quantity: 1,
    condition: "NM",
    finish: price.finish || "normal",
    marketPrice: price.value,
    currency: price.currency,
    priceSource: price.source,
    priceFinish: price.finish,
    updatedAtMarket: card.tcgplayer?.updatedAt || card.cardmarket?.updatedAt || "",
    raw: card,
  };
}

function pickPokemonTcgPrice(card) {
  const tcgPlayerPrices = card.tcgplayer?.prices || {};
  const order = ["holofoil", "reverseHolofoil", "normal", "1stEditionHolofoil", "unlimitedHolofoil"];

  for (const key of order) {
    const price = tcgPlayerPrices[key]?.market || tcgPlayerPrices[key]?.mid;
    if (price) {
      return {
        value: Number(price),
        currency: "USD",
        source: "tcgplayer",
        finish: key,
      };
    }
  }

  const cardmarket = card.cardmarket?.prices;
  if (cardmarket?.averageSellPrice) {
    return {
      value: Number(cardmarket.averageSellPrice),
      currency: "EUR",
      source: "cardmarket",
      finish: "normal",
    };
  }

  return noPrice(card.cardmarket ? "cardmarket" : "manual");
}

function pickPricePatch(card) {
  return {
    marketPrice: card.marketPrice,
    currency: card.currency,
    priceSource: card.priceSource,
    priceFinish: card.priceFinish,
    marketReferenceId: card.marketReferenceId || card.id,
    marketReferenceName: card.marketReferenceName || card.name,
    updatedAtMarket: card.updatedAtMarket,
  };
}

function noPrice(source) {
  return {
    value: 0,
    marketPrice: 0,
    currency: source === "cardmarket" ? "EUR" : "USD",
    source,
    priceSource: source,
    finish: "normal",
  };
}

function scorePriceCandidate(candidate, card) {
  let score = 0;
  if (candidate.name.toLowerCase() === (card.englishName || "").toLowerCase()) score += 20;
  if (candidate.number && candidate.number === card.number) score += 8;
  if (candidate.marketPrice) score += 5;
  if (candidate.setName && card.setName && candidate.setName === card.setName) score += 5;
  return score;
}

function rankResults(cards) {
  const languageOrder = { ja: 0, ko: 1, en: 2 };
  return [...cards].sort((a, b) => {
    if (a.source !== b.source) return a.source === "tcgdex" ? -1 : 1;
    if (a.language !== b.language) return (languageOrder[a.language] ?? 9) - (languageOrder[b.language] ?? 9);
    return String(a.number || "").localeCompare(String(b.number || ""), "ko", { numeric: true });
  });
}

function dedupeCards(cards) {
  const seen = new Set();
  return cards.filter((card) => {
    const key = `${card.source}:${card.language}:${card.id}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function withPriceHistorySeed(card) {
  if (!Number(card.marketPrice || 0)) return card;
  return {
    ...card,
    priceHistory: [
      {
        capturedAt: new Date().toISOString(),
        currency: card.currency || "USD",
        price: Number(card.marketPrice),
        source: card.priceSource || card.source,
      },
    ],
  };
}

function extractLocalNumber(value) {
  const match = String(value || "")
    .trim()
    .match(/^#?(\d+[a-zA-Z-]*)(?:\/\d+[a-zA-Z-]*)?$/);
  return match?.[1] || "";
}

function pokemonQueryValue(value) {
  const normalized = String(value)
    .replace(/[^\p{L}\p{N}\s.'-]/gu, " ")
    .trim()
    .split(/\s+/)
    .join(" ");

  return normalized.includes(" ") ? `"${normalized}"` : normalized;
}

function toTcgDexImage(imageUrl, quality) {
  if (!imageUrl) return "";
  if (/\.(png|jpg|webp)$/i.test(imageUrl)) return imageUrl;
  return `${imageUrl}/${quality}.webp`;
}

function defaultFinish(variants = {}) {
  if (variants.holo) return "holo";
  if (variants.reverse) return "reverse";
  if (variants.normal) return "normal";
  return "unknown";
}

function hasLatin(value) {
  return /[A-Za-z]/.test(value || "");
}
