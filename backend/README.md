# LinkView Python Backend

Standalone Python backend for receiving continuous data from LinkView web apps. It uses only the Python standard library.

## Run

```powershell
cd backend
$env:LINKVIEW_API_TOKEN = "change-me"
python .\linkview_backend.py --host 0.0.0.0 --port 8765
```

For local-only development, keep the default host:

```powershell
python .\linkview_backend.py
```

Data is stored under `backend/data/` by default and is ignored by git.

## Security

The data handled here can include location, audio, video, and photos. Set `LINKVIEW_API_TOKEN` before exposing the server to any network. The app or web page should send the token as either:

```text
Authorization: Bearer change-me
X-LinkView-Token: change-me
```

Useful environment variables:

```text
LINKVIEW_BACKEND_HOST          Default: 127.0.0.1
LINKVIEW_BACKEND_PORT          Default: 8765
LINKVIEW_DATA_DIR              Default: backend/data
LINKVIEW_API_TOKEN             Default: empty, not recommended outside local testing
LINKVIEW_ALLOWED_ORIGIN        Default: *
LINKVIEW_MAX_UPLOAD_BYTES      Default: 134217728
```

## Endpoints

```text
GET  /health
GET  /api/info
GET  /api/devices
POST /api/location
POST /api/event
POST /api/media/raw
POST /api/media/base64
POST /api/media/chunk
```

### Location

```http
POST /api/location
Content-Type: application/json
Authorization: Bearer change-me
```

```json
{
  "device_id": "phone-1",
  "latitude": 37.4219983,
  "longitude": -122.084,
  "accuracy": 5,
  "client_time": "2026-05-16T10:00:00.000Z",
  "extra": {
    "source": "LinkView"
  }
}
```

### Raw Media

Use this when the web app already has a `Blob` or binary buffer.

```http
POST /api/media/raw?device_id=phone-1&kind=image&filename=photo.jpg
Content-Type: image/jpeg
Authorization: Bearer change-me
```

Body: raw bytes.

`kind` can be `image`, `audio`, `video`, or `file`.

### Base64 Media

Use this for small payloads or quick compatibility tests.

```http
POST /api/media/base64
Content-Type: application/json
Authorization: Bearer change-me
```

```json
{
  "device_id": "phone-1",
  "kind": "audio",
  "filename": "clip.webm",
  "data": "BASE64_BYTES"
}
```

### Chunked Media

Use this for continuous audio or video recording. Send ordered chunks from `MediaRecorder`.

```http
POST /api/media/chunk?device_id=phone-1&kind=video&session_id=session-1&index=0&filename=capture.webm
Content-Type: video/webm
Authorization: Bearer change-me
```

For the last chunk, add `final=1`:

```text
/api/media/chunk?device_id=phone-1&kind=video&session_id=session-1&index=12&final=1&filename=capture.webm
```

The backend stores chunks under `data/chunks/` and assembles final media under `data/media/`.

## LinkView Web App Example

```js
const API_BASE = "http://192.168.1.10:8765";
const API_TOKEN = "change-me";
const DEVICE_ID = "phone-1";

const headers = {
  Authorization: `Bearer ${API_TOKEN}`,
};

async function sendLocation() {
  await LinkView.requestPermissions(["location"]);
  navigator.geolocation.watchPosition((position) => {
    fetch(`${API_BASE}/api/location`, {
      method: "POST",
      headers: {
        ...headers,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        device_id: DEVICE_ID,
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracy: position.coords.accuracy,
        client_time: new Date().toISOString(),
      }),
    });
  });
}

async function streamCamera() {
  await LinkView.requestPermissions(["camera", "microphone"]);
  const stream = await navigator.mediaDevices.getUserMedia({
    video: true,
    audio: true,
  });
  const sessionId = crypto.randomUUID();
  let index = 0;
  const recorder = new MediaRecorder(stream, {
    mimeType: "video/webm",
  });

  recorder.ondataavailable = async (event) => {
    if (!event.data.size) return;
    await fetch(
      `${API_BASE}/api/media/chunk?device_id=${DEVICE_ID}&kind=video&session_id=${sessionId}&index=${index++}&filename=capture.webm`,
      {
        method: "POST",
        headers,
        body: event.data,
      }
    );
  };

  recorder.start(3000);
}
```

## Test

```powershell
cd backend
python -m unittest -v
```
