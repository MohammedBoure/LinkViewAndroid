const PERMISSIONS = ["camera", "microphone", "location", "storage"];

const state = {
  cameraStream: null,
  micStream: null,
  audioContext: null,
  meterFrame: 0,
};

const ui = {
  bridgeState: document.querySelector("#bridgeState"),
  output: document.querySelector("#output"),
  cameraState: document.querySelector("#cameraState"),
  microphoneState: document.querySelector("#microphoneState"),
  locationState: document.querySelector("#locationState"),
  storageState: document.querySelector("#storageState"),
  cameraPreview: document.querySelector("#cameraPreview"),
  micLevel: document.querySelector("#micLevel"),
  micReading: document.querySelector("#micReading"),
  latValue: document.querySelector("#latValue"),
  lngValue: document.querySelector("#lngValue"),
  fileInput: document.querySelector("#fileInput"),
  fileSummary: document.querySelector("#fileSummary"),
};

function show(value) {
  ui.output.textContent = JSON.stringify(value, null, 2);
}

function markBridge(ok) {
  ui.bridgeState.textContent = ok ? "متصل" : "غير متصل";
  ui.bridgeState.className = `badge ${ok ? "ok" : "fail"}`;
}

function markPermission(name, granted) {
  const dot = ui[`${name}State`];
  if (!dot) return;
  dot.className = `dot ${granted ? "ok" : "fail"}`;
}

function hasNativeBridge() {
  return Boolean(window.LinkView && window.LinkView.isNative);
}

async function refreshPermissions() {
  if (!hasNativeBridge()) {
    markBridge(false);
    show({ ok: false, error: "LinkView bridge is not available" });
    return;
  }

  markBridge(true);
  const payload = LinkView.getPermissions(PERMISSIONS);
  for (const permission of payload.permissions || []) {
    markPermission(permission.name, permission.granted);
  }
  show({
    permissions: payload,
    device: LinkView.getDeviceInfo(),
    links: LinkView.getLinks(),
  });
}

async function requestPermission(name) {
  if (!hasNativeBridge()) {
    refreshPermissions();
    return;
  }

  try {
    const payload = await LinkView.requestPermissions([name]);
    show(payload);
  } catch (error) {
    show(error);
  }
  refreshPermissions();
}

async function startCamera() {
  await requestPermission("camera");
  stopCamera();

  try {
    state.cameraStream = await navigator.mediaDevices.getUserMedia({
      video: true,
      audio: false,
    });
    ui.cameraPreview.srcObject = state.cameraStream;
    await ui.cameraPreview.play();
    markPermission("camera", true);
    show({ ok: true, camera: "streaming" });
  } catch (error) {
    markPermission("camera", false);
    show({ ok: false, camera: error.message });
  }
}

function stopCamera() {
  if (!state.cameraStream) return;
  for (const track of state.cameraStream.getTracks()) {
    track.stop();
  }
  state.cameraStream = null;
  ui.cameraPreview.srcObject = null;
}

async function startMicrophone() {
  await requestPermission("microphone");
  stopMicrophone();

  try {
    state.micStream = await navigator.mediaDevices.getUserMedia({
      video: false,
      audio: true,
    });
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) {
      throw new Error("AudioContext is not available");
    }
    state.audioContext = new AudioContextClass();
    const source = state.audioContext.createMediaStreamSource(state.micStream);
    const analyser = state.audioContext.createAnalyser();
    analyser.fftSize = 256;
    source.connect(analyser);

    const data = new Uint8Array(analyser.frequencyBinCount);
    const tick = () => {
      analyser.getByteFrequencyData(data);
      const average = data.reduce((sum, value) => sum + value, 0) / data.length;
      const percent = Math.min(100, Math.round((average / 128) * 100));
      ui.micLevel.style.width = `${percent}%`;
      ui.micReading.textContent = `${percent}%`;
      state.meterFrame = requestAnimationFrame(tick);
    };
    tick();
    markPermission("microphone", true);
    show({ ok: true, microphone: "streaming" });
  } catch (error) {
    markPermission("microphone", false);
    show({ ok: false, microphone: error.message });
  }
}

function stopMicrophone() {
  if (state.meterFrame) {
    cancelAnimationFrame(state.meterFrame);
    state.meterFrame = 0;
  }
  if (state.micStream) {
    for (const track of state.micStream.getTracks()) {
      track.stop();
    }
    state.micStream = null;
  }
  if (state.audioContext) {
    state.audioContext.close();
    state.audioContext = null;
  }
  ui.micLevel.style.width = "0%";
  ui.micReading.textContent = "0%";
}

async function readLocation() {
  await requestPermission("location");
  if (!navigator.geolocation) {
    show({ ok: false, location: "geolocation is not available" });
    return;
  }

  navigator.geolocation.getCurrentPosition(
    (position) => {
      ui.latValue.textContent = position.coords.latitude.toFixed(6);
      ui.lngValue.textContent = position.coords.longitude.toFixed(6);
      markPermission("location", true);
      show({
        ok: true,
        location: {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy: position.coords.accuracy,
        },
      });
    },
    (error) => {
      markPermission("location", false);
      show({ ok: false, location: error.message });
    },
    { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 }
  );
}

function updateFileSummary() {
  const files = Array.from(ui.fileInput.files || []);
  if (files.length === 0) {
    ui.fileSummary.textContent = "اختر ملفاً";
    return;
  }

  const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
  ui.fileSummary.textContent = `${files.length} ملف / ${Math.round(totalBytes / 1024)} KB`;
  markPermission("storage", true);
  show({
    ok: true,
    files: files.map((file) => ({
      name: file.name,
      type: file.type || "unknown",
      size: file.size,
    })),
  });
}

function bind() {
  document.querySelector("#cameraPermissionBtn").addEventListener("click", () => requestPermission("camera"));
  document.querySelector("#microphonePermissionBtn").addEventListener("click", () => requestPermission("microphone"));
  document.querySelector("#locationPermissionBtn").addEventListener("click", () => requestPermission("location"));
  document.querySelector("#storagePermissionBtn").addEventListener("click", () => requestPermission("storage"));
  document.querySelector("#cameraStartBtn").addEventListener("click", startCamera);
  document.querySelector("#cameraStopBtn").addEventListener("click", stopCamera);
  document.querySelector("#micStartBtn").addEventListener("click", startMicrophone);
  document.querySelector("#micStopBtn").addEventListener("click", stopMicrophone);
  document.querySelector("#locationReadBtn").addEventListener("click", readLocation);
  document.querySelector("#refreshBtn").addEventListener("click", refreshPermissions);
  ui.fileInput.addEventListener("change", updateFileSummary);
}

document.addEventListener("linkviewready", refreshPermissions);
document.addEventListener("DOMContentLoaded", () => {
  bind();
  refreshPermissions();
});
