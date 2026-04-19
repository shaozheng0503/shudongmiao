from __future__ import annotations

import asyncio
import json
from collections.abc import Sequence

import websockets

from app.domain.schemas import ChatMessage, ModelOutput
from app.settings import settings


class MiniCPMClient:
    def __init__(self, ws_url: str | None = None) -> None:
        self.ws_url = ws_url or settings.minicpm_ws_url

    async def chat(self, messages: list[ChatMessage]) -> ModelOutput:
        if settings.use_mock_model:
            return self._mock_output(messages)

        protocols = self._protocol_candidates()
        last_error: Exception | None = None
        for protocol in protocols:
            try:
                payload = self._build_payload(messages, protocol=protocol)
                response_text, raw_payload = await self._send_and_receive(payload)
                return ModelOutput(
                    text=response_text,
                    raw_payload=raw_payload,
                    protocol=protocol,
                )
            except Exception as exc:  # pragma: no cover - network dependent
                last_error = exc
        if last_error is not None:
            raise last_error
        raise RuntimeError("MiniCPM client failed without a captured error.")

    async def _send_and_receive(self, payload: dict) -> tuple[str, dict]:
        connect_kwargs = {
            "max_size": 8 * 1024 * 1024,
        }
        if settings.minicpm_ws_origin:
            connect_kwargs["origin"] = settings.minicpm_ws_origin
        if settings.minicpm_ws_subprotocol:
            connect_kwargs["subprotocols"] = [settings.minicpm_ws_subprotocol]

        async with asyncio.timeout(settings.minicpm_request_timeout_seconds):
            async with websockets.connect(self.ws_url, **connect_kwargs) as socket:
                await socket.send(json.dumps(payload, ensure_ascii=False))
                return await self._receive_response(socket)

    async def _receive_response(
        self, socket: websockets.WebSocketClientProtocol
    ) -> tuple[str, dict]:
        chunks: list[str] = []
        payloads: list[dict] = []

        while True:
            raw = await socket.recv()
            if isinstance(raw, bytes):
                raw = raw.decode("utf-8")
            try:
                message = json.loads(raw)
            except json.JSONDecodeError:
                return raw, {"raw_text": raw}

            payloads.append(message)
            chunk = self._extract_text(message)
            if chunk:
                chunks.append(chunk)
            if self._is_terminal_message(message):
                break

            if "response" in message and isinstance(message["response"], str):
                # 一些服务端会一次性返回最终结果而没有 done 事件。
                return message["response"], message

        full_text = self._finalize_text(chunks, payloads)
        return full_text, {"events": payloads}

    def _build_payload(self, messages: Sequence[ChatMessage], *, protocol: str) -> dict:
        serializers = {
            "flat": self._serialize_flat_message,
            "openai": self._serialize_openai_message,
        }
        serializer = serializers[protocol]
        payload: dict = {
            "messages": [serializer(message) for message in messages],
            "stream": False,
        }
        if settings.minicpm_send_generation_params:
            payload["temperature"] = settings.minicpm_temperature
            if settings.minicpm_max_tokens is not None:
                payload["max_tokens"] = settings.minicpm_max_tokens
        return payload

    @staticmethod
    def _serialize_flat_message(message: ChatMessage) -> dict:
        content: list[dict] = []
        for part in message.content:
            entry = {"type": part.type.value}
            if part.text is not None:
                entry["text"] = part.text
            if part.data is not None:
                entry["data"] = MiniCPMClient._normalize_binary_payload(part.data)
            if part.mime_type is not None:
                entry["mime_type"] = part.mime_type
            if part.metadata:
                entry["metadata"] = part.metadata
            content.append(entry)
        return {"role": message.role, "content": content}

    @staticmethod
    def _serialize_openai_message(message: ChatMessage) -> dict:
        content: list[dict] = []
        for part in message.content:
            if part.type.value == "text":
                content.append({"type": "text", "text": part.text or ""})
                continue

            if part.type.value == "image":
                content.append(
                    {
                        "type": "image_url",
                        "image_url": {"url": part.data or ""},
                    }
                )
                continue

            if part.type.value in {"audio", "video"}:
                content.append(
                    {
                        "type": part.type.value,
                        "data": MiniCPMClient._normalize_binary_payload(part.data or ""),
                        "mime_type": part.mime_type,
                        "metadata": part.metadata,
                    }
                )
        return {"role": message.role, "content": content}

    @staticmethod
    def _extract_text(message: dict) -> str:
        if isinstance(message.get("text_delta"), str):
            return message["text_delta"]
        if isinstance(message.get("text"), str):
            return message["text"]
        if isinstance(message.get("content"), str):
            return message["content"]
        if isinstance(message.get("delta"), str):
            return message["delta"]
        if isinstance(message.get("response"), str):
            return message["response"]
        choices = message.get("choices")
        if isinstance(choices, list) and choices:
            delta = choices[0].get("delta") or {}
            if isinstance(delta, dict) and isinstance(delta.get("content"), str):
                return delta["content"]
            msg = choices[0].get("message") or {}
            if isinstance(msg, dict) and isinstance(msg.get("content"), str):
                return msg["content"]
        return ""

    @staticmethod
    def _is_terminal_message(message: dict) -> bool:
        message_type = str(message.get("type", "")).lower()
        event = str(message.get("event", "")).lower()
        status = str(message.get("status", "")).lower()
        done = message.get("done")
        return (
            bool(done)
            or message_type in {"done", "error"}
            or event in {"done", "completed", "finish"}
            or status in {"done", "completed"}
        )

    def _mock_output(self, messages: list[ChatMessage]) -> ModelOutput:
        serialized = [self._serialize_flat_message(message) for message in messages]
        last_text = ""
        for message in reversed(serialized):
            for part in message["content"]:
                if part["type"] == "text" and part.get("text"):
                    last_text = part["text"]
                    break
            if last_text:
                break
        sample = {
            "summary": "本喵现在有点警觉，像是轻度应激喵。",
            "emotion_assessment": {
                "primary": "stress_alert",
                "confidence": 0.73,
                "signals": ["身体收紧", "警觉姿态"],
            },
            "health_risk_assessment": {
                "level": "medium",
                "score": 0.56,
                "triggers": ["用户描述需要继续核实"],
                "reason": "本喵现在主要像是应激或不舒服，但还没看到足够高危证据喵。",
            },
            "evidence": {
                "visual": ["媒体内容提示警觉姿态"],
                "textual": [last_text[:120]],
                "knowledge_refs": ["emotion_stress_signals"],
            },
            "care_suggestions": [
                "先帮本喵减少噪音和陌生刺激喵。",
                "记录我近 12 小时的进食、饮水、排尿和精神状态喵。",
            ],
            "urgent_flags": [],
            "followup_questions": [
                "本喵最近有没有躲藏、拒食或反复蹲猫砂盆喵？",
                "我这种状态持续多久了喵？",
            ],
            "disclaimer": "本结果仅用于风险提示与照护建议，不构成医疗诊断。",
        }
        return ModelOutput(
            text=json.dumps(sample, ensure_ascii=False),
            raw_payload={"mock": True},
            protocol="mock",
        )

    @staticmethod
    def _protocol_candidates() -> list[str]:
        configured = settings.minicpm_ws_protocol.strip().lower()
        if configured == "auto":
            return ["flat", "openai"]
        return [configured]

    @staticmethod
    def _normalize_binary_payload(data: str) -> str:
        prefix = ";base64,"
        if prefix in data:
            return data.split(prefix, 1)[1]
        return data

    @staticmethod
    def _finalize_text(chunks: list[str], payloads: list[dict]) -> str:
        if payloads:
            tail = payloads[-1]
            if str(tail.get("type", "")).lower() == "done" and isinstance(tail.get("text"), str):
                return tail["text"].strip()

        full_text = "".join(chunks).strip()
        if full_text:
            return full_text

        if payloads:
            tail = payloads[-1]
            return (
                tail.get("content")
                or tail.get("text")
                or tail.get("response")
                or json.dumps(tail, ensure_ascii=False)
            )
        return ""


minicpm_client = MiniCPMClient()
