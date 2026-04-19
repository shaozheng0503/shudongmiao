#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ ! -d ".venv" ]; then
  python3.11 -m venv .venv
fi

. .venv/bin/activate
python -m pip install -U pip >/dev/null
python -m pip install fastapi uvicorn python-multipart pydantic httpx pillow websockets pytest >/dev/null

HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8000}"

exec uvicorn app.main:app --host "$HOST" --port "$PORT"
