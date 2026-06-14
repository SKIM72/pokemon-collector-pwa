#!/usr/bin/env python3
"""Build a Supabase card-image index using the same MediaPipe model as Android."""

from __future__ import annotations

import argparse
import io
import os
import sys
import time
from typing import Any

import mediapipe as mp
import numpy as np
import requests
from PIL import Image

TCGDEX_API = "https://api.tcgdex.net/v2"
EMBEDDING_SIZE = 1024
PAGE_SIZE = 100


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--language", choices=("ja", "ko", "en"), default="ja")
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--start-page", type=int, default=1)
    parser.add_argument("--model", required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--skip-existing",
        action=argparse.BooleanOptionalAction,
        default=True,
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    supabase_url = os.environ.get("SUPABASE_URL", "").rstrip("/")
    service_role_key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if not args.dry_run and (not supabase_url or not service_role_key):
        print("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required.", file=sys.stderr)
        return 2

    base_options = mp.tasks.BaseOptions(model_asset_path=args.model)
    options = mp.tasks.vision.ImageEmbedderOptions(
        base_options=base_options,
        l2_normalize=True,
        quantize=False,
    )
    indexed = 0
    skipped = 0
    skipped_existing = 0
    page = args.start_page
    existing_ids = (
        load_existing_ids(supabase_url, service_role_key, args.language)
        if args.skip_existing and not args.dry_run
        else set()
    )
    if existing_ids:
        print(f"existing {args.language} embeddings: {len(existing_ids)}")

    with mp.tasks.vision.ImageEmbedder.create_from_options(options) as embedder:
        while indexed < args.limit:
            cards = fetch_json(
                f"{TCGDEX_API}/{args.language}/cards",
                params={
                    "pagination:page": page,
                    "pagination:itemsPerPage": PAGE_SIZE,
                },
            )
            if not cards:
                break

            for summary in cards:
                if indexed >= args.limit:
                    break
                if not summary.get("image"):
                    skipped += 1
                    continue
                if summary.get("id") in existing_ids:
                    skipped_existing += 1
                    continue

                try:
                    card = fetch_json(
                        f"{TCGDEX_API}/{args.language}/cards/{summary['id']}",
                    )
                    image_url = image_url_for(card.get("image") or summary["image"])
                    image = fetch_image(image_url)
                    embedding = create_embedding(embedder, image)
                    row = card_row(card, args.language, image_url, embedding, image)
                    if args.dry_run:
                        print(
                            f"[dry-run] {row['external_id']} {row['name']} "
                            f"{len(embedding)}d {row['perceptual_hash']}",
                        )
                    else:
                        upsert_row(supabase_url, service_role_key, row)
                        print(f"[indexed] {row['external_id']} {row['name']}")
                        existing_ids.add(row["external_id"])
                    indexed += 1
                except Exception as error:  # Keep a long import moving.
                    if (
                        isinstance(error, requests.HTTPError)
                        and error.response is not None
                        and error.response.status_code in (401, 403)
                    ):
                        print(
                            f"Supabase rejected the indexer credentials: {error}",
                            file=sys.stderr,
                        )
                        return 3
                    skipped += 1
                    print(f"[skip] {summary.get('id')}: {error}", file=sys.stderr)
                time.sleep(0.04)
            page += 1

    print(
        "complete: "
        f"indexed={indexed}, skipped={skipped}, "
        f"skipped_existing={skipped_existing}, next_page={page}",
    )
    return 0


def fetch_json(url: str, params: dict[str, Any] | None = None) -> Any:
    response = requests.get(url, params=params, timeout=30)
    response.raise_for_status()
    return response.json()


def fetch_image(url: str) -> Image.Image:
    response = requests.get(url, timeout=30)
    response.raise_for_status()
    return Image.open(io.BytesIO(response.content)).convert("RGB")


def image_url_for(base_url: str) -> str:
    if base_url.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
        return base_url
    return f"{base_url}/high.webp"


def create_embedding(
    embedder: mp.tasks.vision.ImageEmbedder,
    image: Image.Image,
) -> list[float]:
    mp_image = mp.Image(
        image_format=mp.ImageFormat.SRGB,
        data=np.asarray(image, dtype=np.uint8),
    )
    result = embedder.embed(mp_image)
    vector = result.embeddings[0].embedding
    if len(vector) != EMBEDDING_SIZE:
        raise ValueError(f"expected {EMBEDDING_SIZE} dimensions, got {len(vector)}")
    return [float(value) for value in vector]


def card_row(
    card: dict[str, Any],
    language: str,
    image_url: str,
    embedding: list[float],
    image: Image.Image,
) -> dict[str, Any]:
    set_data = card.get("set") or {}
    price, currency, price_source = market_price(card, language)
    return {
        "source": "tcgdex",
        "external_id": card["id"],
        "language": language,
        "name": card.get("name") or "Unknown",
        "set_id": set_data.get("id"),
        "set_name": set_data.get("name"),
        "card_number": card.get("localId"),
        "rarity": card.get("rarity"),
        "image_url": image_url,
        "image_high_url": image_url,
        "market_price": price,
        "currency": currency,
        "price_source": price_source,
        "embedding": embedding,
        "perceptual_hash": difference_hash(image),
        "metadata": {
            "variants": card.get("variants") or {},
            "updated": card.get("updated"),
        },
    }


def market_price(
    card: dict[str, Any],
    language: str,
) -> tuple[float | None, str, str | None]:
    pricing = card.get("pricing") or {}
    tcgplayer = pricing.get("tcgplayer") or {}
    if language == "en":
        for finish in ("holofoil", "normal", "reverse-holofoil", "1st-edition-holofoil"):
            values = tcgplayer.get(finish) or {}
            value = values.get("marketPrice") or values.get("midPrice")
            if value:
                return float(value), "USD", "tcgplayer"

    cardmarket = pricing.get("cardmarket") or {}
    value = cardmarket.get("trend") or cardmarket.get("avg30") or cardmarket.get("avg")
    if value:
        return float(value), cardmarket.get("unit") or "EUR", "cardmarket"

    return None, {"ja": "JPY", "ko": "KRW", "en": "USD"}[language], None


def difference_hash(image: Image.Image) -> str:
    grayscale = image.convert("L").resize((9, 8), Image.Resampling.LANCZOS)
    pixels = np.asarray(grayscale)
    bits = pixels[:, :-1] > pixels[:, 1:]
    value = 0
    for index, enabled in enumerate(bits.flatten()):
        if enabled:
            value |= 1 << index
    return f"{value:016x}"


def upsert_row(url: str, key: str, row: dict[str, Any]) -> None:
    response = requests.post(
        f"{url}/rest/v1/card_reference_embeddings",
        params={"on_conflict": "source,external_id,language"},
        headers={
            "Authorization": f"Bearer {key}",
            "apikey": key,
            "Content-Type": "application/json",
            "Prefer": "resolution=merge-duplicates,return=minimal",
        },
        json=row,
        timeout=45,
    )
    response.raise_for_status()


def load_existing_ids(url: str, key: str, language: str) -> set[str]:
    existing: set[str] = set()
    page_size = 1000
    offset = 0
    while True:
        response = requests.get(
            f"{url}/rest/v1/card_reference_embeddings",
            params={
                "select": "external_id",
                "source": "eq.tcgdex",
                "language": f"eq.{language}",
                "limit": str(page_size),
                "offset": str(offset),
            },
            headers={
                "Authorization": f"Bearer {key}",
                "apikey": key,
            },
            timeout=45,
        )
        response.raise_for_status()
        rows = response.json()
        existing.update(
            row["external_id"]
            for row in rows
            if row.get("external_id")
        )
        if len(rows) < page_size:
            break
        offset += page_size
    return existing


if __name__ == "__main__":
    raise SystemExit(main())
