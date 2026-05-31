import { supabase } from "./supabaseClient.js";

export async function loadUserSettings(userId) {
  const { data, error } = await supabase
    .from("user_settings")
    .select("display_currency")
    .eq("user_id", userId)
    .maybeSingle();

  if (error) throw error;
  return data;
}

export async function saveUserSettings(userId, settings) {
  const { error } = await supabase.from("user_settings").upsert(
    {
      user_id: userId,
      display_currency: settings.displayCurrency,
    },
    { onConflict: "user_id" },
  );

  if (error) throw error;
}

export async function loadCloudCollection(userId) {
  const [{ data: cards, error: cardError }, { data: snapshots, error: snapshotError }] = await Promise.all([
    supabase
      .from("collection_cards")
      .select("*")
      .eq("user_id", userId)
      .order("updated_at", { ascending: false }),
    supabase
      .from("card_price_snapshots")
      .select("*")
      .eq("user_id", userId)
      .order("captured_at", { ascending: true }),
  ]);

  if (cardError) throw cardError;
  if (snapshotError) throw snapshotError;

  const snapshotsByCard = new Map();
  for (const snapshot of snapshots || []) {
    const list = snapshotsByCard.get(snapshot.collection_card_id) || [];
    list.push(snapshot);
    snapshotsByCard.set(snapshot.collection_card_id, list);
  }

  return (cards || []).map((row) => rowToCard(row, snapshotsByCard.get(row.id) || []));
}

export async function addCloudCard(userId, card, collection) {
  const existing = collection.find(
    (item) =>
      item.source === card.source &&
      item.language === card.language &&
      item.externalId === card.id &&
      item.condition === (card.condition || "NM") &&
      item.finish === (card.finish || "normal"),
  );

  if (existing) {
    const updated = await updateCloudCard(userId, existing.uid, {
      quantity: Number(existing.quantity || 0) + Number(card.quantity || 1),
      marketPrice: Number(card.marketPrice || existing.marketPrice || 0),
      currency: card.currency || existing.currency,
      priceSource: card.priceSource || existing.priceSource,
      priceFinish: card.priceFinish || existing.priceFinish,
      marketReferenceId: card.marketReferenceId || existing.marketReferenceId,
      marketReferenceName: card.marketReferenceName || existing.marketReferenceName,
      marketUpdatedAt: card.updatedAtMarket || existing.updatedAtMarket,
      lastPriceSyncAt: card.marketPrice ? new Date().toISOString() : existing.lastPriceSyncAt,
    });
    return updated;
  }

  const payload = cardToRow(userId, card);
  const { data, error } = await supabase.from("collection_cards").insert(payload).select("*").single();
  if (error) throw error;

  if (Number(card.marketPrice || 0) > 0) {
    await insertPriceSnapshot(userId, data.id, card);
  }

  return rowToCard(data, []);
}

export async function updateCloudCard(userId, cardId, patch) {
  const rowPatch = patchToRow(patch);
  const { data, error } = await supabase
    .from("collection_cards")
    .update(rowPatch)
    .eq("id", cardId)
    .eq("user_id", userId)
    .select("*")
    .single();

  if (error) throw error;

  if (hasPricePatch(patch)) {
    await insertPriceSnapshot(userId, cardId, {
      marketPrice: patch.marketPrice,
      currency: patch.currency || data.currency,
      priceSource: patch.priceSource || data.price_source,
      marketReferenceId: patch.marketReferenceId || data.market_reference_id,
    });
  }

  return rowToCard(data, []);
}

export async function deleteCloudCard(userId, cardId) {
  const { error } = await supabase.from("collection_cards").delete().eq("id", cardId).eq("user_id", userId);
  if (error) throw error;
}

export async function loadPortfolioSnapshots(userId) {
  const { data, error } = await supabase
    .from("portfolio_snapshots")
    .select("*")
    .eq("user_id", userId)
    .order("captured_at", { ascending: true });

  if (error) throw error;
  return (data || []).map((row) => ({
    capturedAt: row.captured_at,
    baseUsd: Number(row.base_usd_value || 0),
    displayCurrency: row.display_currency,
    totalValue: Number(row.total_value || 0),
  }));
}

export async function insertPortfolioSnapshot(userId, snapshot) {
  const { error } = await supabase.from("portfolio_snapshots").insert({
    user_id: userId,
    display_currency: snapshot.displayCurrency,
    total_value: snapshot.totalValue,
    base_usd_value: snapshot.baseUsd,
    captured_at: snapshot.capturedAt || new Date().toISOString(),
  });

  if (error) throw error;
}

export async function replaceCloudCollection(userId, cards) {
  const { error: deleteError } = await supabase.from("collection_cards").delete().eq("user_id", userId);
  if (deleteError) throw deleteError;

  if (!cards.length) return [];

  const rows = cards.map((card) => cardToRow(userId, normalizeImportedCard(card)));
  const { data, error } = await supabase.from("collection_cards").insert(rows).select("*");
  if (error) throw error;
  return (data || []).map((row) => rowToCard(row, []));
}

function cardToRow(userId, card) {
  return {
    user_id: userId,
    source: card.source || "tcgdex",
    language: card.language || "ja",
    external_id: card.id || card.externalId,
    name: card.name || "Unknown",
    localized_names: card.localizedNames || {},
    english_name: card.englishName || card.localizedNames?.en || null,
    set_id: card.setId || null,
    set_name: card.setName || null,
    card_number: card.number || null,
    rarity: card.rarity || null,
    image_url: card.image || null,
    image_high_url: card.imageHigh || null,
    condition: card.condition || "NM",
    finish: card.finish || "normal",
    quantity: Number(card.quantity || 1),
    market_price: Number(card.marketPrice || 0),
    currency: card.currency || "JPY",
    price_source: card.priceSource || "manual",
    price_finish: card.priceFinish || null,
    market_reference_id: card.marketReferenceId || null,
    market_reference_name: card.marketReferenceName || null,
    market_updated_at: card.updatedAtMarket || null,
    last_price_sync_at: card.marketPrice ? new Date().toISOString() : null,
    raw: card.raw || {},
    ...(card.isFavorite || card.is_favorite ? { is_favorite: true } : {}),
  };
}

function patchToRow(patch) {
  const map = {
    condition: "condition",
    finish: "finish",
    isFavorite: "is_favorite",
    quantity: "quantity",
    marketPrice: "market_price",
    currency: "currency",
    priceSource: "price_source",
    priceFinish: "price_finish",
    marketReferenceId: "market_reference_id",
    marketReferenceName: "market_reference_name",
    updatedAtMarket: "market_updated_at",
    marketUpdatedAt: "market_updated_at",
    lastPriceSyncAt: "last_price_sync_at",
  };

  return Object.entries(patch).reduce((row, [key, value]) => {
    const column = map[key];
    if (!column) return row;
    row[column] = key === "marketPrice" || key === "quantity" ? Number(value || 0) : value;
    return row;
  }, {});
}

function rowToCard(row, snapshots) {
  return {
    uid: row.id,
    cloudId: row.id,
    id: row.external_id,
    externalId: row.external_id,
    source: row.source,
    language: row.language,
    providerLabel: row.source === "pokemontcg" ? "Pokemon TCG 가격" : `TCGdex ${row.language.toUpperCase()}`,
    name: row.name,
    localizedNames: row.localized_names || {},
    englishName: row.english_name || row.localized_names?.en || "",
    setId: row.set_id || "",
    setName: row.set_name || "",
    number: row.card_number || "",
    rarity: row.rarity || "",
    image: row.image_url || "",
    imageHigh: row.image_high_url || row.image_url || "",
    condition: row.condition || "NM",
    finish: row.finish || "normal",
    isFavorite: Boolean(row.is_favorite),
    quantity: Number(row.quantity || 0),
    marketPrice: Number(row.market_price || 0),
    currency: row.currency || "JPY",
    priceSource: row.price_source || "manual",
    priceFinish: row.price_finish || "",
    marketReferenceId: row.market_reference_id || "",
    marketReferenceName: row.market_reference_name || "",
    updatedAtMarket: row.market_updated_at || "",
    lastPriceSyncAt: row.last_price_sync_at || "",
    addedAt: row.created_at,
    updatedAt: row.updated_at,
    raw: row.raw || {},
    priceHistory: snapshots.map((snapshot) => ({
      capturedAt: snapshot.captured_at,
      currency: snapshot.currency,
      price: Number(snapshot.price || 0),
      source: snapshot.source,
    })),
  };
}

async function insertPriceSnapshot(userId, cardId, card) {
  const price = Number(card.marketPrice || 0);
  if (!price) return;

  const { error } = await supabase.from("card_price_snapshots").insert({
    user_id: userId,
    collection_card_id: cardId,
    price,
    currency: card.currency || "USD",
    source: card.priceSource || "manual",
    market_reference_id: card.marketReferenceId || null,
    captured_at: new Date().toISOString(),
  });

  if (error) throw error;
}

function hasPricePatch(patch) {
  return Object.prototype.hasOwnProperty.call(patch, "marketPrice") && Number(patch.marketPrice || 0) > 0;
}

function normalizeImportedCard(card) {
  return {
    ...card,
    id: card.id || card.externalId,
    localizedNames: card.localizedNames || card.localized_names || {},
    isFavorite: Boolean(card.isFavorite || card.is_favorite || false),
    marketPrice: Number(card.marketPrice || card.market_price || 0),
    priceSource: card.priceSource || card.price_source || "manual",
  };
}
