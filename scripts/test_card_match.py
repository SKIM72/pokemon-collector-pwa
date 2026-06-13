#!/usr/bin/env python3
"""Smoke-test the deployed card-image-match function with a TCGdex card."""

from __future__ import annotations

import argparse
import os
import sys

import mediapipe as mp
import requests
from PIL import Image, ImageEnhance, ImageFilter

from index_tcgdex_cards import (
    TCGDEX_API,
    create_embedding,
    difference_hash,
    fetch_image,
    fetch_json,
    image_url_for,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--language", choices=("ja", "ko", "en"), default="ja")
    parser.add_argument("--card-id", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--simulate-camera", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    supabase_url = os.environ.get("SUPABASE_URL", "").rstrip("/")
    anon_key = os.environ.get("SUPABASE_ANON_KEY", "")
    if not supabase_url or not anon_key:
        print("SUPABASE_URL and SUPABASE_ANON_KEY are required.", file=sys.stderr)
        return 2

    card = fetch_json(f"{TCGDEX_API}/{args.language}/cards/{args.card_id}")
    image_url = image_url_for(card["image"])
    image = fetch_image(image_url)
    if args.simulate_camera:
        image = simulate_camera(image)

    options = mp.tasks.vision.ImageEmbedderOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=args.model),
        l2_normalize=True,
        quantize=False,
    )
    with mp.tasks.vision.ImageEmbedder.create_from_options(options) as embedder:
        embedding = create_embedding(embedder, image)

    response = requests.post(
        f"{supabase_url}/functions/v1/card-image-match",
        headers={
            "Authorization": f"Bearer {anon_key}",
            "apikey": anon_key,
            "Content-Type": "application/json",
        },
        json={
            "language": args.language,
            "embedding": embedding,
            "perceptualHash": difference_hash(image),
            "matchCount": 5,
            "minSimilarity": 0.55,
        },
        timeout=45,
    )
    response.raise_for_status()
    payload = response.json()
    candidates = payload.get("candidates") or []
    if not candidates:
        print("No candidates returned.", file=sys.stderr)
        return 3

    for index, candidate in enumerate(candidates, start=1):
        print(
            f"{index}. {candidate['id']} {candidate['name']} "
            f"{candidate.get('confidence', 0):.4f}",
        )

    return 0 if candidates[0]["id"] == args.card_id else 4


def simulate_camera(image):
    image = ImageEnhance.Brightness(image).enhance(0.82)
    image = ImageEnhance.Contrast(image).enhance(0.92)
    image = image.filter(ImageFilter.GaussianBlur(radius=0.7))
    return image.rotate(
        3.5,
        resample=Image.Resampling.BICUBIC,
        expand=False,
        fillcolor=(35, 35, 35),
    )


if __name__ == "__main__":
    raise SystemExit(main())
