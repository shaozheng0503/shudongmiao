from __future__ import annotations

import argparse
import asyncio
import base64
import json
import mimetypes
from pathlib import Path

import websockets


async def probe(
    url: str,
    text: str,
    origin: str | None = None,
    image_file: Path | None = None,
    video_file: Path | None = None,
) -> None:
    content: list[dict[str, str]] = [
        {
            "type": "text",
            "text": text,
        }
    ]
    if image_file is not None:
        mime_type = mimetypes.guess_type(image_file.name)[0] or "image/jpeg"
        image_data = base64.b64encode(image_file.read_bytes()).decode("utf-8")
        content.append(
            {
                "type": "image",
                "data": image_data,
                "mime_type": mime_type,
            }
        )
    if video_file is not None:
        mime_type = mimetypes.guess_type(video_file.name)[0] or "video/mp4"
        video_data = base64.b64encode(video_file.read_bytes()).decode("utf-8")
        content.append(
            {
                "type": "video",
                "data": video_data,
                "mime_type": mime_type,
            }
        )

    payload = {
        "messages": [
            {
                "role": "user",
                "content": content,
            }
        ],
        "stream": False,
    }
    connect_kwargs = {"max_size": 8 * 1024 * 1024, "open_timeout": 20}
    if origin:
        connect_kwargs["origin"] = origin

    async with websockets.connect(url, **connect_kwargs) as ws:
        await ws.send(json.dumps(payload, ensure_ascii=False))
        for _ in range(20):
            try:
                message = await asyncio.wait_for(ws.recv(), timeout=12)
                print(message)
                parsed = json.loads(message)
                if parsed.get("type") in {"done", "error"}:
                    break
            except TimeoutError:
                print("TimeoutError: no more events received")
                break


def main() -> None:
    parser = argparse.ArgumentParser(description="Probe MiniCPM WebSocket endpoint.")
    parser.add_argument(
        "--url",
        default="wss://deployment-452-qdufnumb-8006.550w.link/ws/chat",
        help="WebSocket endpoint URL",
    )
    parser.add_argument(
        "--origin",
        default=None,
        help="Optional Origin header",
    )
    parser.add_argument(
        "--text",
        default='请只返回一个 JSON 对象：{"pong":true}',
        help="Probe text prompt",
    )
    parser.add_argument(
        "--image-file",
        type=Path,
        default=None,
        help="Optional image file path for multimodal probing",
    )
    parser.add_argument(
        "--video-file",
        type=Path,
        default=None,
        help="Optional video file path for multimodal probing",
    )
    args = parser.parse_args()
    asyncio.run(
        probe(
            url=args.url,
            text=args.text,
            origin=args.origin,
            image_file=args.image_file,
            video_file=args.video_file,
        )
    )


if __name__ == "__main__":
    main()
