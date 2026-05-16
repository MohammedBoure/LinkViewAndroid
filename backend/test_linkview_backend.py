import base64
import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from linkview_backend import LinkViewBackendHandler, LinkViewHTTPServer, Settings


class BackendServerCase(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.settings = Settings(
            host="127.0.0.1",
            port=0,
            data_dir=Path(self.temp_dir.name),
            api_token="test-token",
            allowed_origin="*",
            max_upload_bytes=1024 * 1024,
        )
        self.server = LinkViewHTTPServer(("127.0.0.1", 0), LinkViewBackendHandler, self.settings)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temp_dir.cleanup()

    def request(self, path, body=None, headers=None, method="GET"):
        headers = headers or {}
        data = body
        if isinstance(body, dict):
            data = json.dumps(body).encode("utf-8")
            headers = {"Content-Type": "application/json", **headers}
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        with urlopen(request, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))

    def auth_headers(self, content_type=None):
        headers = {"Authorization": "Bearer test-token"}
        if content_type:
            headers["Content-Type"] = content_type
        return headers

    def test_health_is_public(self):
        payload = self.request("/health")
        self.assertTrue(payload["ok"])

    def test_api_requires_token(self):
        with self.assertRaises(HTTPError) as context:
            self.request("/api/info")
        self.assertEqual(context.exception.code, 401)

    def test_location_event_is_saved(self):
        payload = self.request(
            "/api/location",
            {
                "device_id": "phone-1",
                "latitude": 31.2,
                "longitude": 30.1,
                "accuracy": 8,
            },
            self.auth_headers(),
            method="POST",
        )

        self.assertTrue(payload["ok"])
        latest = self.settings.data_dir / "devices" / "phone-1" / "latest_location.json"
        self.assertTrue(latest.exists())
        saved = json.loads(latest.read_text(encoding="utf-8"))
        self.assertEqual(saved["latitude"], 31.2)

    def test_raw_media_upload_is_saved(self):
        payload = self.request(
            "/api/media/raw?device_id=phone-1&kind=image&filename=photo.jpg",
            b"image-bytes",
            self.auth_headers("image/jpeg"),
            method="POST",
        )

        self.assertTrue(payload["ok"])
        media_path = self.settings.data_dir / payload["media"]["path"]
        self.assertEqual(media_path.read_bytes(), b"image-bytes")

    def test_base64_media_upload_is_saved(self):
        encoded = base64.b64encode(b"audio-bytes").decode("ascii")
        payload = self.request(
            "/api/media/base64",
            {
                "device_id": "phone-1",
                "kind": "audio",
                "filename": "clip.webm",
                "data": encoded,
            },
            self.auth_headers(),
            method="POST",
        )

        self.assertTrue(payload["ok"])
        media_path = self.settings.data_dir / payload["media"]["path"]
        self.assertEqual(media_path.read_bytes(), b"audio-bytes")

    def test_chunk_upload_assembles_final_media(self):
        headers = self.auth_headers("application/octet-stream")
        self.request(
            "/api/media/chunk?device_id=phone-1&kind=video&session_id=s1&index=0&filename=test.webm",
            b"hello ",
            headers,
            method="POST",
        )
        payload = self.request(
            "/api/media/chunk?device_id=phone-1&kind=video&session_id=s1&index=1&final=1&filename=test.webm",
            b"world",
            headers,
            method="POST",
        )

        self.assertTrue(payload["ok"])
        media_path = self.settings.data_dir / payload["media"]["path"]
        self.assertEqual(media_path.read_bytes(), b"hello world")


if __name__ == "__main__":
    unittest.main()
