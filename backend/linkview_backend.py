#!/usr/bin/env python3
"""
Small standalone backend for LinkView web apps.

The server intentionally uses only the Python standard library so it can be
started on a development machine without installing packages.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse


DEFAULT_MAX_UPLOAD_BYTES = 128 * 1024 * 1024
VALID_MEDIA_KINDS = {"image", "audio", "video", "file"}


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    data_dir: Path
    api_token: str
    allowed_origin: str
    max_upload_bytes: int


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def day_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d")


def safe_slug(value: str, fallback: str = "unknown") -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip())
    cleaned = cleaned.strip("._")
    return cleaned[:80] or fallback


def json_bytes(payload: Any) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def parse_json_bytes(raw: bytes) -> dict[str, Any]:
    if not raw:
        return {}
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object")
    return value


def append_jsonl(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as output:
        output.write(json.dumps(payload, ensure_ascii=False, sort_keys=True))
        output.write("\n")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True),
        encoding="utf-8",
    )


def normalize_media_kind(kind: str) -> str:
    normalized = safe_slug(kind.lower(), "file")
    return normalized if normalized in VALID_MEDIA_KINDS else "file"


def request_id() -> str:
    return uuid.uuid4().hex


class LinkViewBackendHandler(BaseHTTPRequestHandler):
    server_version = "LinkViewBackend/1.0"

    @property
    def settings(self) -> Settings:
        return self.server.settings  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print(f"{timestamp} {self.address_string()} {fmt % args}")

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_common_headers()
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self.send_json({"ok": True, "time": utc_now()})
            return
        if parsed.path == "/api/info":
            if not self.authorized():
                return
            self.send_json(
                {
                    "ok": True,
                    "name": "LinkView Backend",
                    "version": 1,
                    "max_upload_bytes": self.settings.max_upload_bytes,
                    "media_kinds": sorted(VALID_MEDIA_KINDS),
                    "token_required": bool(self.settings.api_token),
                }
            )
            return
        if parsed.path == "/api/devices":
            if not self.authorized():
                return
            self.send_json({"ok": True, "devices": self.list_devices()})
            return
        self.send_error_json(HTTPStatus.NOT_FOUND, "not_found")

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if not self.authorized():
            return

        if parsed.path == "/api/location":
            self.handle_location()
            return
        if parsed.path == "/api/event":
            self.handle_event()
            return
        if parsed.path == "/api/media/raw":
            self.handle_raw_media(parsed)
            return
        if parsed.path == "/api/media/base64":
            self.handle_base64_media()
            return
        if parsed.path == "/api/media/chunk":
            self.handle_media_chunk(parsed)
            return

        self.send_error_json(HTTPStatus.NOT_FOUND, "not_found")

    def authorized(self) -> bool:
        token = self.settings.api_token
        if not token:
            return True

        auth_header = self.headers.get("Authorization", "")
        header_token = self.headers.get("X-LinkView-Token", "")
        bearer_token = ""
        if auth_header.lower().startswith("bearer "):
            bearer_token = auth_header[7:].strip()

        if token in {header_token, bearer_token}:
            return True

        self.send_error_json(HTTPStatus.UNAUTHORIZED, "unauthorized")
        return False

    def read_body(self) -> bytes:
        content_length = int(self.headers.get("Content-Length", "0") or "0")
        if content_length > self.settings.max_upload_bytes:
            raise ValueError("Payload is larger than max_upload_bytes")
        return self.rfile.read(content_length)

    def handle_location(self) -> None:
        try:
            payload = parse_json_bytes(self.read_body())
            latitude = float(payload["latitude"])
            longitude = float(payload["longitude"])
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            self.send_error_json(HTTPStatus.BAD_REQUEST, "invalid_location", str(exc))
            return

        device_id = safe_slug(str(payload.get("device_id", "unknown")))
        event = {
            "id": request_id(),
            "type": "location",
            "received_at": utc_now(),
            "device_id": device_id,
            "latitude": latitude,
            "longitude": longitude,
            "accuracy": payload.get("accuracy"),
            "altitude": payload.get("altitude"),
            "speed": payload.get("speed"),
            "heading": payload.get("heading"),
            "client_time": payload.get("client_time"),
            "extra": payload.get("extra", {}),
        }

        append_jsonl(self.events_path("location"), event)
        write_json(self.device_path(device_id) / "latest_location.json", event)
        self.send_json({"ok": True, "event_id": event["id"]})

    def handle_event(self) -> None:
        try:
            payload = parse_json_bytes(self.read_body())
        except (ValueError, json.JSONDecodeError) as exc:
            self.send_error_json(HTTPStatus.BAD_REQUEST, "invalid_event", str(exc))
            return

        event_type = safe_slug(str(payload.get("type", "event")), "event")
        device_id = safe_slug(str(payload.get("device_id", "unknown")))
        event = {
            "id": request_id(),
            "type": event_type,
            "received_at": utc_now(),
            "device_id": device_id,
            "payload": payload.get("payload", payload),
        }
        append_jsonl(self.events_path(event_type), event)
        self.send_json({"ok": True, "event_id": event["id"]})

    def handle_raw_media(self, parsed: Any) -> None:
        query = parse_qs(parsed.query)
        device_id = safe_slug(query_value(query, "device_id", "unknown"))
        kind = normalize_media_kind(query_value(query, "kind", "file"))
        filename = query_value(query, "filename", f"{kind}.bin")

        try:
            data = self.read_body()
        except ValueError as exc:
            self.send_error_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "payload_too_large", str(exc))
            return

        media_path = self.save_media(device_id, kind, filename, data)
        self.send_json({"ok": True, "media": media_path})

    def handle_base64_media(self) -> None:
        try:
            payload = parse_json_bytes(self.read_body())
            raw_data = str(payload["data"])
        except (KeyError, ValueError, json.JSONDecodeError) as exc:
            self.send_error_json(HTTPStatus.BAD_REQUEST, "invalid_media", str(exc))
            return

        if "," in raw_data and raw_data.strip().startswith("data:"):
            raw_data = raw_data.split(",", 1)[1]

        try:
            media_bytes = base64.b64decode(raw_data, validate=True)
        except ValueError as exc:
            self.send_error_json(HTTPStatus.BAD_REQUEST, "invalid_base64", str(exc))
            return

        if len(media_bytes) > self.settings.max_upload_bytes:
            self.send_error_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "payload_too_large")
            return

        device_id = safe_slug(str(payload.get("device_id", "unknown")))
        kind = normalize_media_kind(str(payload.get("kind", "file")))
        filename = str(payload.get("filename", f"{kind}.bin"))
        media_path = self.save_media(device_id, kind, filename, media_bytes)
        self.send_json({"ok": True, "media": media_path})

    def handle_media_chunk(self, parsed: Any) -> None:
        query = parse_qs(parsed.query)
        device_id = safe_slug(query_value(query, "device_id", "unknown"))
        session_id = safe_slug(query_value(query, "session_id", request_id()))
        kind = normalize_media_kind(query_value(query, "kind", "file"))
        filename = query_value(query, "filename", f"{session_id}.bin")
        index = int(query_value(query, "index", "0"))
        is_final = query_value(query, "final", "0").lower() in {"1", "true", "yes"}

        try:
            data = self.read_body()
        except ValueError as exc:
            self.send_error_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "payload_too_large", str(exc))
            return

        chunk_dir = self.settings.data_dir / "chunks" / device_id / session_id
        chunk_dir.mkdir(parents=True, exist_ok=True)
        chunk_path = chunk_dir / f"{index:08d}.part"
        chunk_path.write_bytes(data)

        append_jsonl(
            self.events_path("media_chunk"),
            {
                "id": request_id(),
                "type": "media_chunk",
                "received_at": utc_now(),
                "device_id": device_id,
                "session_id": session_id,
                "kind": kind,
                "index": index,
                "bytes": len(data),
                "final": is_final,
            },
        )

        response: dict[str, Any] = {
            "ok": True,
            "session_id": session_id,
            "index": index,
            "bytes": len(data),
            "final": is_final,
        }
        if is_final:
            assembled = self.assemble_chunks(device_id, session_id, kind, filename)
            response["media"] = assembled
        self.send_json(response)

    def assemble_chunks(self, device_id: str, session_id: str, kind: str, filename: str) -> dict[str, Any]:
        chunk_dir = self.settings.data_dir / "chunks" / device_id / session_id
        media_path = self.next_media_path(device_id, kind, filename)
        media_path.parent.mkdir(parents=True, exist_ok=True)

        bytes_written = 0
        with media_path.open("wb") as output:
            for chunk_path in sorted(chunk_dir.glob("*.part")):
                chunk = chunk_path.read_bytes()
                output.write(chunk)
                bytes_written += len(chunk)

        metadata = self.media_metadata(device_id, kind, filename, media_path, bytes_written)
        append_jsonl(self.events_path("media"), metadata)
        return metadata

    def save_media(self, device_id: str, kind: str, filename: str, data: bytes) -> dict[str, Any]:
        media_path = self.next_media_path(device_id, kind, filename)
        media_path.parent.mkdir(parents=True, exist_ok=True)
        media_path.write_bytes(data)

        metadata = self.media_metadata(device_id, kind, filename, media_path, len(data))
        append_jsonl(self.events_path("media"), metadata)
        return metadata

    def media_metadata(
        self,
        device_id: str,
        kind: str,
        filename: str,
        media_path: Path,
        size: int,
    ) -> dict[str, Any]:
        return {
            "id": request_id(),
            "type": "media",
            "received_at": utc_now(),
            "device_id": device_id,
            "kind": kind,
            "filename": filename,
            "size": size,
            "path": str(media_path.relative_to(self.settings.data_dir)),
            "content_type": self.headers.get("Content-Type", "application/octet-stream"),
        }

    def next_media_path(self, device_id: str, kind: str, filename: str) -> Path:
        safe_name = safe_slug(filename, f"{kind}.bin")
        prefix = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
        unique_name = f"{prefix}-{uuid.uuid4().hex[:10]}-{safe_name}"
        return self.settings.data_dir / "media" / device_id / kind / unique_name

    def events_path(self, event_type: str) -> Path:
        return self.settings.data_dir / "events" / f"{safe_slug(event_type)}-{day_stamp()}.jsonl"

    def device_path(self, device_id: str) -> Path:
        return self.settings.data_dir / "devices" / device_id

    def list_devices(self) -> list[dict[str, Any]]:
        devices_dir = self.settings.data_dir / "devices"
        if not devices_dir.exists():
            return []

        devices = []
        for device_dir in sorted(path for path in devices_dir.iterdir() if path.is_dir()):
            latest_location = device_dir / "latest_location.json"
            payload: dict[str, Any] = {"device_id": device_dir.name}
            if latest_location.exists():
                try:
                    payload["latest_location"] = json.loads(latest_location.read_text(encoding="utf-8"))
                except json.JSONDecodeError:
                    payload["latest_location"] = None
            devices.append(payload)
        return devices

    def send_json(self, payload: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json_bytes(payload)
        self.send_response(status)
        self.send_common_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_error_json(self, status: HTTPStatus, error: str, detail: str | None = None) -> None:
        payload: dict[str, Any] = {"ok": False, "error": error}
        if detail:
            payload["detail"] = detail
        self.send_json(payload, status)

    def send_common_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", self.settings.allowed_origin)
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-LinkView-Token")
        self.send_header("Access-Control-Max-Age", "86400")


def query_value(query: dict[str, list[str]], name: str, default: str) -> str:
    values = query.get(name)
    if not values:
        return default
    return values[0] or default


class LinkViewHTTPServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int], handler: type[BaseHTTPRequestHandler], settings: Settings):
        super().__init__(address, handler)
        self.settings = settings


def settings_from_args() -> Settings:
    parser = argparse.ArgumentParser(description="Run the LinkView Python backend.")
    parser.add_argument("--host", default=os.getenv("LINKVIEW_BACKEND_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("LINKVIEW_BACKEND_PORT", "8765")))
    parser.add_argument(
        "--data-dir",
        default=os.getenv("LINKVIEW_DATA_DIR", str(Path(__file__).with_name("data"))),
    )
    parser.add_argument("--token", default=os.getenv("LINKVIEW_API_TOKEN", ""))
    parser.add_argument("--origin", default=os.getenv("LINKVIEW_ALLOWED_ORIGIN", "*"))
    parser.add_argument(
        "--max-upload-bytes",
        type=int,
        default=int(os.getenv("LINKVIEW_MAX_UPLOAD_BYTES", str(DEFAULT_MAX_UPLOAD_BYTES))),
    )
    args = parser.parse_args()
    return Settings(
        host=args.host,
        port=args.port,
        data_dir=Path(args.data_dir).resolve(),
        api_token=args.token,
        allowed_origin=args.origin,
        max_upload_bytes=args.max_upload_bytes,
    )


def main() -> None:
    settings = settings_from_args()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    server = LinkViewHTTPServer(
        (settings.host, settings.port),
        LinkViewBackendHandler,
        settings,
    )
    print(f"LinkView backend listening on http://{settings.host}:{settings.port}")
    print(f"Data directory: {settings.data_dir}")
    if not settings.api_token:
        print("Warning: LINKVIEW_API_TOKEN is not set; /api endpoints are open.")
    server.serve_forever()


if __name__ == "__main__":
    main()
