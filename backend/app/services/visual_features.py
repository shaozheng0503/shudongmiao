from __future__ import annotations

import base64
import io
import math
from pathlib import Path
from typing import TYPE_CHECKING

from PIL import Image, ImageFilter

try:
    import numpy as np
except ImportError:  # pragma: no cover - optional dependency
    np = None

if TYPE_CHECKING:
    from app.domain.schemas import PreparedMedia


def image_bytes_from_data_url(data_url: str) -> bytes:
    if "," not in data_url:
        raise ValueError("invalid data url")
    _, payload = data_url.split(",", 1)
    return base64.b64decode(payload)


def image_embedding_from_bytes(image_bytes: bytes) -> list[float]:
    with Image.open(io.BytesIO(image_bytes)) as image:
        return image_embedding_from_image(image)


def image_embedding_from_path(path: Path) -> list[float]:
    with Image.open(path) as image:
        return image_embedding_from_image(image)


def image_embedding_from_media(media: "PreparedMedia") -> list[float] | None:
    candidates: list[list[float]] = []
    if media.data_url and media.media_type.value == "image":
        candidates.append(image_embedding_from_bytes(image_bytes_from_data_url(media.data_url)))
    else:
        for frame in media.frames[:3]:
            candidates.append(image_embedding_from_bytes(image_bytes_from_data_url(frame.data_url)))
    if not candidates:
        return None
    if np is not None:
        vector = np.mean(np.array(candidates, dtype=np.float32), axis=0)
        return vector.astype(np.float32).tolist()
    size = len(candidates[0])
    return [sum(vector[index] for vector in candidates) / len(candidates) for index in range(size)]


def cosine_similarity(lhs: list[float], rhs: list[float]) -> float:
    if np is not None:
        left = np.array(lhs, dtype=np.float32)
        right = np.array(rhs, dtype=np.float32)
        left_norm = float(np.linalg.norm(left))
        right_norm = float(np.linalg.norm(right))
        if left_norm <= 1e-8 or right_norm <= 1e-8:
            return 0.0
        similarity = float(np.dot(left, right) / (left_norm * right_norm))
        return max(-1.0, min(1.0, similarity))
    left_norm = math.sqrt(sum(value * value for value in lhs))
    right_norm = math.sqrt(sum(value * value for value in rhs))
    if left_norm <= 1e-8 or right_norm <= 1e-8:
        return 0.0
    similarity = sum(left * right for left, right in zip(lhs, rhs)) / (left_norm * right_norm)
    return max(-1.0, min(1.0, similarity))


def image_embedding_from_image(image: Image.Image) -> list[float]:
    rgb = image.convert("RGB")
    rgb.thumbnail((160, 160))

    hist_features: list[float] = []
    for channel in rgb.split():
        hist_raw = channel.histogram()
        hist = [sum(hist_raw[index : index + 16]) for index in range(0, 256, 16)]
        hist_sum = max(float(sum(hist)), 1.0)
        hist_features.extend([value / hist_sum for value in hist])

    gray = rgb.convert("L").resize((16, 16))
    gray_vec = [value / 255.0 for value in gray.tobytes()]
    gray_mean = sum(gray_vec) / max(len(gray_vec), 1)
    gray_std = math.sqrt(sum((value - gray_mean) ** 2 for value in gray_vec) / max(len(gray_vec), 1))

    edge = gray.filter(ImageFilter.FIND_EDGES)
    edge_vec = [value / 255.0 for value in edge.resize((8, 8)).tobytes()]

    vector = [
        *hist_features,
        *gray_vec,
        *edge_vec,
        gray_mean,
        gray_std,
        math.sqrt(gray_mean**2 + gray_std**2),
    ]
    return vector
