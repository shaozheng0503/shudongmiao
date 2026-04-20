from __future__ import annotations

import argparse
import json
import random
import subprocess
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


@dataclass
class SampleResult:
    image: str
    primary: str
    summary: str
    risk: str
    knowledge_refs: list[str]
    retrieved_count: int


def _collect_images(root: Path) -> list[Path]:
    exts = {".png", ".jpg", ".jpeg", ".webp"}
    return sorted(p for p in root.rglob("*") if p.suffix.lower() in exts)


def _pick_images(images: list[Path], sample_count: int, seed: int) -> list[Path]:
    if len(images) <= sample_count:
        return images
    random.seed(seed)
    return random.sample(images, sample_count)


def _call_analyze(endpoint: str, image: Path, timeout_sec: int) -> dict:
    cmd = [
        "curl",
        "--max-time",
        str(timeout_sec),
        "-sS",
        "-X",
        "POST",
        endpoint,
        "-F",
        "input_text=请判断猫咪情绪和风险，返回最贴切情绪标签",
        "-F",
        "media_type=image",
        "-F",
        "scene_hint=general",
        "-F",
        f"image_file=@{image}",
    ]
    out = subprocess.check_output(cmd, text=True)
    return json.loads(out)


def main() -> None:
    parser = argparse.ArgumentParser(description="Batch regression for emotion diversity and knowledge linkage.")
    parser.add_argument(
        "--endpoint",
        default="http://127.0.0.1:8000/api/v1/analyze",
        help="Analyze API endpoint",
    )
    parser.add_argument(
        "--image-root",
        default="/Users/huangshaozheng/Desktop/ai创变/backend/knowledge_assets/docx_import",
        help="Image root for regression samples",
    )
    parser.add_argument("--samples", type=int, default=20, help="Number of images to test")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    parser.add_argument("--timeout", type=int, default=45, help="Per-request timeout seconds")
    args = parser.parse_args()

    root = Path(args.image_root)
    images = _collect_images(root)
    if not images:
        raise SystemExit(f"No images found under: {root}")

    picked = _pick_images(images, args.samples, args.seed)
    results: list[SampleResult] = []

    for image in picked:
        payload = _call_analyze(args.endpoint, image, args.timeout)
        refs = [x for x in payload.get("evidence", {}).get("knowledge_refs", []) if str(x).strip()]
        results.append(
            SampleResult(
                image=str(image.relative_to(root)),
                primary=payload.get("emotion_assessment", {}).get("primary", "unknown"),
                summary=payload.get("summary", "").strip(),
                risk=payload.get("health_risk_assessment", {}).get("level", "low"),
                knowledge_refs=refs,
                retrieved_count=len(payload.get("retrieved_knowledge", [])),
            )
        )

    primary_counter = Counter(item.primary for item in results)
    signature_counter = Counter((item.primary, item.summary) for item in results)
    repeated = sum(count for _, count in signature_counter.items() if count > 1)
    unique_summary = len(set(item.summary for item in results))
    knowledge_ref_hits = sum(1 for item in results if item.knowledge_refs)
    retrieved_hits = sum(1 for item in results if item.retrieved_count > 0)

    print(f"SAMPLES={len(results)}")
    print(f"UNIQUE_PRIMARY={len(primary_counter)}")
    print(f"PRIMARY_DISTRIBUTION={dict(primary_counter)}")
    print(f"UNIQUE_SUMMARY={unique_summary}")
    print(f"MAX_DUP_PRIMARY_SUMMARY={signature_counter.most_common(1)[0]}")
    print(f"DUP_PRIMARY_SUMMARY_RATIO={repeated / max(len(results), 1):.2%}")
    print(f"KNOWLEDGE_REF_HIT_RATIO={knowledge_ref_hits / max(len(results), 1):.2%}")
    print(f"RETRIEVED_KNOWLEDGE_HIT_RATIO={retrieved_hits / max(len(results), 1):.2%}")
    print("---DETAILS---")
    for item in results:
        print(
            f"{item.image} | primary={item.primary} | risk={item.risk} | "
            f"summary={item.summary} | refs={item.knowledge_refs} | retrieved={item.retrieved_count}"
        )


if __name__ == "__main__":
    main()
