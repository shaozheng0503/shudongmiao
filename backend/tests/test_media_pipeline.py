from pathlib import Path

from app.services.media_pipeline import MediaPipeline


def test_probe_video_reads_ffprobe_metadata() -> None:
    sample = Path("scripts/assets/cute_kitten_3s.mp4")
    if not sample.exists():
        return

    metadata = MediaPipeline()._probe_video(sample)

    assert float(metadata["duration"]) > 0
