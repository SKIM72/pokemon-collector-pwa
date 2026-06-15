import { createClient } from "https://esm.sh/@supabase/supabase-js@2.57.4";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
};

const EMBEDDING_SIZE = 1024;
const DEFAULT_MATCH_COUNT = 5;
const DEFAULT_MIN_SIMILARITY = 0.55;

type MatchRequest = {
  embedding?: number[];
  language?: "ja" | "ko" | "en";
  perceptualHash?: string;
  matchCount?: number;
  minSimilarity?: number;
  imageBase64?: string;
};

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (request.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  try {
    const body = (await request.json()) as MatchRequest;
    const language = body.language;
    const embedding = body.embedding;

    if (!language || !["ja", "ko", "en"].includes(language)) {
      return json({ error: "language must be ja, ko, or en" }, 400);
    }

    if (!Array.isArray(embedding)) {
      const message = body.imageBase64
        ? "This endpoint now expects an on-device embedding instead of imageBase64."
        : "embedding is required";
      return json({ error: message, expectedDimensions: EMBEDDING_SIZE }, 400);
    }

    if (
      embedding.length !== EMBEDDING_SIZE ||
      embedding.some((value) => !Number.isFinite(value))
    ) {
      return json(
        {
          error: `embedding must contain ${EMBEDDING_SIZE} finite numbers`,
          receivedDimensions: embedding.length,
        },
        400,
      );
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!supabaseUrl || !serviceRoleKey) {
      return json({ error: "Supabase function environment is incomplete" }, 500);
    }

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });
    const matchCount = clampInteger(body.matchCount, 1, 12, DEFAULT_MATCH_COUNT);
    const minSimilarity = clampNumber(
      body.minSimilarity,
      0,
      1,
      DEFAULT_MIN_SIMILARITY,
    );

    const { data, error } = await supabase.rpc("match_card_embeddings", {
      query_embedding: embedding,
      match_language: language,
      match_count: matchCount,
      min_similarity: minSimilarity,
    });

    if (error) {
      console.error("match_card_embeddings failed", error);
      return json({ error: "Vector search failed" }, 500);
    }

    const candidates = (data ?? []).map((row: Record<string, unknown>) => ({
      id: row.external_id,
      name: row.name,
      setId: row.set_id,
      setName: row.set_name,
      number: row.card_number,
      rarity: row.rarity,
      imageUrl: row.image_high_url ?? row.image_url,
      imageHighUrl: row.image_high_url,
      marketPrice: row.market_price,
      currency: row.currency ?? defaultCurrency(language),
      priceSource: row.price_source,
      confidence: Number(row.similarity ?? 0),
      language: row.language,
      source: row.source,
    }));

    return json({
      card: candidates[0] ?? null,
      candidates,
      query: {
        language,
        perceptualHash: body.perceptualHash ?? null,
      },
    });
  } catch (error) {
    console.error("card-image-match failed", error);
    return json({ error: "Invalid request" }, 400);
  }
});

function json(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      ...corsHeaders,
      "Content-Type": "application/json; charset=utf-8",
    },
  });
}

function clampInteger(
  value: number | undefined,
  minimum: number,
  maximum: number,
  fallback: number,
) {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(maximum, Math.max(minimum, Math.round(value as number)));
}

function clampNumber(
  value: number | undefined,
  minimum: number,
  maximum: number,
  fallback: number,
) {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(maximum, Math.max(minimum, value as number));
}

function defaultCurrency(language: string) {
  if (language === "ja") return "JPY";
  if (language === "ko") return "KRW";
  return "USD";
}
