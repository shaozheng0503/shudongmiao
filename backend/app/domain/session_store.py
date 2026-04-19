from __future__ import annotations

from datetime import datetime
from threading import Lock
from uuid import uuid4

from app.domain.schemas import AnalyzeResponse, SceneHint, SessionState, SessionTurn


class InMemorySessionStore:
    def __init__(self) -> None:
        self._sessions: dict[str, SessionState] = {}
        self._lock = Lock()

    def get_or_create(
        self, session_id: str | None = None, scene_hint: SceneHint = SceneHint.GENERAL
    ) -> SessionState:
        with self._lock:
            resolved_id = session_id or str(uuid4())
            existing = self._sessions.get(resolved_id)
            if existing is not None:
                existing.scene_hint = scene_hint or existing.scene_hint
                existing.updated_at = datetime.utcnow()
                return existing

            created = SessionState(session_id=resolved_id, scene_hint=scene_hint)
            self._sessions[resolved_id] = created
            return created

    def get(self, session_id: str) -> SessionState | None:
        return self._sessions.get(session_id)

    def append_turn(self, session_id: str, role: str, text: str) -> None:
        with self._lock:
            session = self._sessions[session_id]
            session.history.append(SessionTurn(role=role, text=text))
            session.updated_at = datetime.utcnow()

    def save_response(self, session_id: str, response: AnalyzeResponse) -> None:
        with self._lock:
            session = self._sessions[session_id]
            session.latest_response = response
            session.updated_at = datetime.utcnow()


session_store = InMemorySessionStore()
