from __future__ import annotations

import asyncio
import base64
import hashlib
import io
import mimetypes
import shutil
import subprocess
import tempfile
from pathlib import Path

from fastapi import HTTPException, UploadFile, status
from PIL import Image

from app.domain.schemas import MediaFrame, MediaType, PreparedMedia
from app.settings import settings

try:
    import cv2
except ImportError:  # pragma: no cover - optional dependency
    cv2 = None

try:
    import numpy as np
except ImportError:  # pragma: no cover - optional dependency
    np = None


class MediaPipeline:
    def __init__(self) -> None:
        settings.temp_dir.mkdir(parents=True, exist_ok=True)

    async def prepare_upload(self, upload: UploadFile, media_type: MediaType) -> PreparedMedia:
        suffix = Path(upload.filename or f"upload.{media_type.value}").suffix or ".bin"
        temp_file = Path(tempfile.mkstemp(suffix=suffix, dir=settings.temp_dir)[1])
        try:
            content = await upload.read()
            content_sha1 = hashlib.sha1(content).hexdigest()
            temp_file.write_bytes(content)
            if media_type is MediaType.IMAGE:
                if len(content) > settings.max_image_bytes:
                    raise HTTPException(
                        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        detail="图片过大，请压缩后重试。",
                    )
                return await asyncio.to_thread(self._prepare_image, temp_file, content_sha1)

            if len(content) > settings.max_video_bytes:
                raise HTTPException(
                    status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                    detail="视频过大，请控制在 40MB 内。",
                )
            return await asyncio.to_thread(self._prepare_video, temp_file, content_sha1)
        finally:
            if temp_file.exists():
                temp_file.unlink(missing_ok=True)

    def _prepare_image(self, path: Path, content_sha1: str) -> PreparedMedia:
        with Image.open(path) as image:
            image = image.convert("RGB")
            width, height = image.size
            buffer = io.BytesIO()
            image.thumbnail((1440, 1440))
            image.save(buffer, format="JPEG", quality=88)
        data_url = "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("utf-8")
        return PreparedMedia(
            media_type=MediaType.IMAGE,
            mime_type="image/jpeg",
            data_url=data_url,
            width=width,
            height=height,
            metadata={"source": "image_upload", "content_sha1": content_sha1},
        )

    def _prepare_video(self, path: Path, content_sha1: str) -> PreparedMedia:
        metadata = self._probe_video(path)
        duration = float(metadata.get("duration", 0.0))
        mime_type = mimetypes.guess_type(path.name)[0] or "video/mp4"
        if duration > settings.max_video_seconds:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"视频时长超过 {settings.max_video_seconds:g} 秒，请缩短后重试。",
            )

        if settings.video_transport_mode.strip().lower() == "native":
            video_data = base64.b64encode(path.read_bytes()).decode("utf-8")
            return PreparedMedia(
                media_type=MediaType.VIDEO,
                mime_type=mime_type,
                data_url=f"data:{mime_type};base64,{video_data}",
                duration_seconds=duration,
                width=int(metadata.get("width", 0)) or None,
                height=int(metadata.get("height", 0)) or None,
                metadata={"source": "native_video_upload", "content_sha1": content_sha1},
            )

        frames = self._extract_key_frames(path, max_frames=settings.max_frame_count)
        if not frames:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="视频关键帧抽取失败，请重新拍摄更清晰的视频。",
            )

        return PreparedMedia(
            media_type=MediaType.VIDEO,
            mime_type=mime_type,
            frames=frames,
            duration_seconds=duration,
            width=int(metadata.get("width", 0)) or None,
            height=int(metadata.get("height", 0)) or None,
            metadata={"frame_count": len(frames), "source": "video_keyframes", "content_sha1": content_sha1},
        )

    def _probe_video(self, path: Path) -> dict[str, float | int]:
        ffprobe = shutil.which("ffprobe")
        if ffprobe:
            result = subprocess.run(
                [
                    ffprobe,
                    "-v",
                    "error",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "stream=width,height:format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=0",
                    str(path),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            if result.returncode == 0:
                parsed: dict[str, float | int] = {}
                for line in result.stdout.splitlines():
                    if "=" not in line:
                        continue
                    key, value = line.split("=", 1)
                    parsed[key] = float(value) if "." in value else int(value)
                return parsed

        if cv2 is None:
            return {"duration": 0.0, "width": 0, "height": 0}

        capture = cv2.VideoCapture(str(path))
        fps = capture.get(cv2.CAP_PROP_FPS) or 1.0
        frame_count = capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0.0
        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
        capture.release()
        return {
            "duration": frame_count / fps if fps else 0.0,
            "width": width,
            "height": height,
        }

    def _extract_key_frames(self, path: Path, max_frames: int) -> list[MediaFrame]:
        if cv2 is None or np is None:
            return self._extract_key_frames_with_ffmpeg(path, max_frames)

        capture = cv2.VideoCapture(str(path))
        total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        fps = capture.get(cv2.CAP_PROP_FPS) or 1.0
        if total_frames <= 0:
            capture.release()
            return []

        candidate_indexes = set(np.linspace(0, total_frames - 1, num=min(max_frames, total_frames), dtype=int).tolist())
        previous_gray = None
        motion_scores: list[tuple[float, int]] = []
        index = 0

        while True:
            ok, frame = capture.read()
            if not ok:
                break
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            if previous_gray is not None:
                diff = cv2.absdiff(previous_gray, gray)
                score = float(np.mean(diff))
                motion_scores.append((score, index))
            previous_gray = gray
            index += 1

        capture.release()
        motion_scores.sort(reverse=True)
        for _, frame_index in motion_scores[: max(0, max_frames // 2)]:
            candidate_indexes.add(frame_index)

        capture = cv2.VideoCapture(str(path))
        selected_frames: list[MediaFrame] = []
        for frame_index in sorted(candidate_indexes)[:max_frames]:
            capture.set(cv2.CAP_PROP_POS_FRAMES, frame_index)
            ok, frame = capture.read()
            if not ok:
                continue
            height, width = frame.shape[:2]
            image = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            image.thumbnail((1120, 1120))
            buffer = io.BytesIO()
            image.save(buffer, format="JPEG", quality=86)
            data_url = "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("utf-8")
            selected_frames.append(
                MediaFrame(
                    timestamp_ms=int((frame_index / fps) * 1000),
                    data_url=data_url,
                    width=width,
                    height=height,
                    score=round(frame_index / max(total_frames, 1), 4),
                )
            )
        capture.release()
        return selected_frames

    def _extract_key_frames_with_ffmpeg(self, path: Path, max_frames: int) -> list[MediaFrame]:
        ffmpeg = shutil.which("ffmpeg")
        if ffmpeg is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="当前环境缺少视频处理依赖，请安装 ffmpeg 或 OpenCV。",
            )

        output_dir = Path(tempfile.mkdtemp(dir=settings.temp_dir))
        try:
            output_pattern = str(output_dir / "frame_%03d.jpg")
            subprocess.run(
                [
                    ffmpeg,
                    "-i",
                    str(path),
                    "-vf",
                    f"fps=1,scale='min(1120,iw)':-2",
                    "-frames:v",
                    str(max_frames),
                    output_pattern,
                ],
                check=True,
                capture_output=True,
            )
            frames: list[MediaFrame] = []
            for index, frame_path in enumerate(sorted(output_dir.glob("frame_*.jpg"))):
                data = frame_path.read_bytes()
                data_url = "data:image/jpeg;base64," + base64.b64encode(data).decode("utf-8")
                frames.append(
                    MediaFrame(
                        timestamp_ms=index * 1000,
                        data_url=data_url,
                        score=float(index),
                    )
                )
            return frames
        except subprocess.CalledProcessError as exc:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="视频关键帧抽取失败。",
            ) from exc
        finally:
            shutil.rmtree(output_dir, ignore_errors=True)


media_pipeline = MediaPipeline()
