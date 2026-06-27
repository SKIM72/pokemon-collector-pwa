#!/usr/bin/env python3
"""Index official Japanese Pokemon card images in the Supabase vector table."""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote_plus

import mediapipe as mp
import requests

from index_tcgdex_cards import (
    create_embedding,
    difference_hash,
    fetch_image,
    upsert_row,
)

OFFICIAL_SITE = "https://www.pokemon-card.com"
SEARCH_API = f"{OFFICIAL_SITE}/card-search/resultAPI.php"
EMBEDDING_SIZE = 1024
PAGE_SIZE = 39
USER_AGENT = "PokeBinder/0.16 official-card-indexer"
NUMBER_PATTERN = re.compile(
    r"&nbsp;\s*(\d{1,4})\s*&nbsp;\s*/\s*&nbsp;\s*(\d{1,4})",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--query", default="")
    parser.add_argument("--start-page", type=int, default=1)
    parser.add_argument("--pages", type=int, default=1)
    parser.add_argument("--limit", type=int, default=PAGE_SIZE)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--skip-existing",
        action=argparse.BooleanOptionalAction,
        default=True,
    )
    parser.add_argument(
        "--fetch-details",
        action=argparse.BooleanOptionalAction,
        default=True,
    )
    parser.add_argument("--delay", type=float, default=0.08)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    supabase_url = os.environ.get("SUPABASE_URL", "").rstrip("/")
    service_role_key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if not args.dry_run and (not supabase_url or not service_role_key):
        print(
            "SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required.",
            file=sys.stderr,
        )
        return 2

    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": USER_AGENT,
            "Accept-Language": "ja-JP,ja;q=0.9",
        },
    )
    existing_ids = (
        load_existing_ids(supabase_url, service_role_key)
        if args.skip_existing and not args.dry_run
        else set()
    )
    if existing_ids:
        print(f"existing official Japanese embeddings: {len(existing_ids)}")

    base_options = mp.tasks.BaseOptions(model_asset_path=args.model)
    options = mp.tasks.vision.ImageEmbedderOptions(
        base_options=base_options,
        l2_normalize=True,
        quantize=False,
    )
    indexed = 0
    skipped = 0
    skipped_existing = 0
    summaries = card_summaries(session, args)

    with mp.tasks.vision.ImageEmbedder.create_from_options(options) as embedder:
        for summary in summaries:
            if indexed >= args.limit:
                break
            external_id = str(summary.get("cardID") or "")
            image_path = str(summary.get("cardThumbFile") or "")
            name = str(summary.get("cardNameAltText") or "")
            if not external_id or not image_path or not name:
                skipped += 1
                continue
            if external_id in existing_ids:
                skipped_existing += 1
                continue

            try:
                image_url = absolute_url(image_path)
                number = (
                    fetch_card_number(session, external_id)
                    if args.fetch_details
                    else ""
                )
                image = fetch_image(image_url)
                embedding = create_embedding(embedder, image)
                row = card_row(
                    external_id=external_id,
                    name=name,
                    image_url=image_url,
                    number=number,
                    embedding=embedding,
                    image=image,
                )
                if args.dry_run:
                    print(
                        f"[dry-run] {external_id} {name} {number or '-'} "
                        f"{len(embedding)}d {row['perceptual_hash']}",
                    )
                else:
                    upsert_row(supabase_url, service_role_key, row)
                    existing_ids.add(external_id)
                    print(f"[indexed] {external_id} {name} {number or '-'}")
                indexed += 1
            except Exception as error:
                skipped += 1
                print(f"[skip] {external_id}: {error}", file=sys.stderr)
            time.sleep(max(args.delay, 0.0))

    print(
        "complete: "
        f"indexed={indexed}, skipped={skipped}, "
        f"skipped_existing={skipped_existing}",
    )
    return 0


def card_summaries(
    session: requests.Session,
    args: argparse.Namespace,
) -> list[dict[str, Any]]:
    if args.query:
        payload = fetch_search_page(session, query=args.query, page=1)
        return list(payload.get("cardList") or [])

    cards: list[dict[str, Any]] = []
    for page in range(args.start_page, args.start_page + args.pages):
        payload = fetch_search_page(session, query="", page=page)
        page_cards = list(payload.get("cardList") or [])
        if not page_cards:
            break
        cards.extend(page_cards)
        print(
            f"[page] {page}/{payload.get('maxPage', '?')} "
            f"cards={len(page_cards)} total={payload.get('hitCnt', '?')}",
        )
    return cards


def fetch_search_page(
    session: requests.Session,
    query: str,
    page: int,
) -> dict[str, Any]:
    response = session.get(
        SEARCH_API,
        params={
            "keyword": query,
            "regulation_header_search_item0": "all",
            "sm_and_keyword": "true",
            "illust": "",
            "page": page,
        },
        timeout=30,
    )
    response.raise_for_status()
    return response.json()


def fetch_card_number(session: requests.Session, external_id: str) -> str:
    response = session.get(
        f"{OFFICIAL_SITE}/card-search/details.php/card/{external_id}/regu/all",
        timeout=30,
    )
    response.raise_for_status()
    match = NUMBER_PATTERN.search(response.text)
    return f"{match.group(1)}/{match.group(2)}" if match else ""


def card_row(
    external_id: str,
    name: str,
    image_url: str,
    number: str,
    embedding: list[float],
    image: Any,
) -> dict[str, Any]:
    set_id = image_url.split("/large/", 1)[-1].split("/", 1)[0]
    source_url = (
        f"{OFFICIAL_SITE}/card-search/details.php/card/{external_id}/regu/all"
    )
    price_query = quote_plus(f"{name} {number}".strip())
    return {
        "source": "pokemon-card-official",
        "external_id": external_id,
        "language": "ja",
        "name": name,
        "set_id": set_id,
        "set_name": set_id,
        "card_number": number or None,
        "rarity": None,
        "image_url": image_url,
        "image_high_url": image_url,
        "market_price": None,
        "currency": "JPY",
        "price_source": None,
        "embedding": embedding,
        "perceptual_hash": difference_hash(image),
        "source_url": source_url,
        "image_source_url": image_url,
        "price_url": (
            "https://www.cardrush-pokemon.jp/product-list"
            f"?keyword={price_query}"
        ),
        "source_updated_at": datetime.now(timezone.utc).isoformat(),
        "metadata": {
            "official_card_id": external_id,
            "number": number,
        },
    }


def absolute_url(path: str) -> str:
    if path.startswith("http://") or path.startswith("https://"):
        return path
    return f"{OFFICIAL_SITE}{path}"


def load_existing_ids(url: str, key: str) -> set[str]:
    existing: set[str] = set()
    page_size = 1000
    offset = 0
    while True:
        response = requests.get(
            f"{url}/rest/v1/card_reference_embeddings",
            params={
                "select": "external_id",
                "source": "eq.pokemon-card-official",
                "language": "eq.ja",
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
            str(row["external_id"])
            for row in rows
            if row.get("external_id")
        )
        if len(rows) < page_size:
            break
        offset += page_size
    return existing


if __name__ == "__main__":
    raise SystemExit(main())
