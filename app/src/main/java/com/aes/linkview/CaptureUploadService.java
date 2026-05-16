package com.aes.linkview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CaptureUploadService extends Service {
    public static final String ACTION_START = "com.aes.linkview.capture.START";
    public static final String ACTION_STOP = "com.aes.linkview.capture.STOP";
    public static final String EXTRA_SERVER_URL = "server_url";
    public static final String EXTRA_API_TOKEN = "api_token";
    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_LOCATION_INTERVAL_MS = "location_interval_ms";
    public static final String EXTRA_AUDIO_CHUNK_MS = "audio_chunk_ms";
    public static final String EXTRA_PHOTO_INTERVAL_MS = "photo_interval_ms";

    private static final String TAG = "LinkViewCapture";
    private static final String CHANNEL_ID = "linkview_capture";
    private static final int NOTIFICATION_ID = 42;
    private static final long DEFAULT_LOCATION_INTERVAL_MS = 10_000L;
    private static final long DEFAULT_AUDIO_CHUNK_MS = 10_000L;
    private static final long DEFAULT_PHOTO_INTERVAL_MS = 15_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();
    private String serverUrl;
    private String apiToken;
    private String deviceId;
    private long locationIntervalMs;
    private long audioChunkMs;
    private long photoIntervalMs;
    private boolean running;
    private int uploadedLocations;
    private int uploadedAudioChunks;
    private int uploadedPhotos;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraSession;
    private ImageReader imageReader;
    private String cameraId;
    private MediaRecorder mediaRecorder;
    private File currentAudioFile;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundNotification("Starting capture upload");
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            updateNotification("Missing start action");
            stopSelf();
            return START_NOT_STICKY;
        }

        serverUrl = sanitizeServerUrl(intent.getStringExtra(EXTRA_SERVER_URL));
        apiToken = valueOrEmpty(intent.getStringExtra(EXTRA_API_TOKEN));
        deviceId = safeDeviceId(intent.getStringExtra(EXTRA_DEVICE_ID));
        locationIntervalMs = intent.getLongExtra(EXTRA_LOCATION_INTERVAL_MS, DEFAULT_LOCATION_INTERVAL_MS);
        audioChunkMs = intent.getLongExtra(EXTRA_AUDIO_CHUNK_MS, DEFAULT_AUDIO_CHUNK_MS);
        photoIntervalMs = intent.getLongExtra(EXTRA_PHOTO_INTERVAL_MS, DEFAULT_PHOTO_INTERVAL_MS);

        if (serverUrl.isEmpty()) {
            updateNotification("Server URL is missing");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!hasRequiredPermissions()) {
            updateNotification("Permissions are missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        stopCapture();
        running = true;
        acquireWakeLock();
        updateNotification("Uploading location, audio, and photos");
        startLocationUpdates();
        startCameraCapture();
        scheduleAudioRecording(0L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        uploadExecutor.shutdownNow();
        super.onDestroy();
    }

    private boolean hasRequiredPermissions() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                && hasPermission(Manifest.permission.CAMERA)
                && hasPermission(Manifest.permission.RECORD_AUDIO);
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkView:captureUpload");
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void stopCapture() {
        running = false;
        stopLocationUpdates();
        stopAudioRecording(false);
        stopCameraCapture();
        releaseWakeLock();
        updateNotification("Capture upload stopped");
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                uploadLocation(location);
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }
        };

        requestLocationProvider(LocationManager.GPS_PROVIDER);
        requestLocationProvider(LocationManager.NETWORK_PROVIDER);
        Location lastLocation = lastKnownLocation();
        if (lastLocation != null) {
            uploadLocation(lastLocation);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestLocationProvider(String provider) {
        try {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(
                        provider,
                        locationIntervalMs,
                        0f,
                        locationListener,
                        Looper.getMainLooper()
                );
            }
        } catch (IllegalArgumentException | SecurityException exception) {
            Log.w(TAG, "Could not request location provider " + provider, exception);
        }
    }

    @SuppressLint("MissingPermission")
    private Location lastKnownLocation() {
        Location gps = null;
        Location network = null;
        try {
            gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (IllegalArgumentException | SecurityException exception) {
            Log.w(TAG, "Could not read last GPS location", exception);
        }
        try {
            network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (IllegalArgumentException | SecurityException exception) {
            Log.w(TAG, "Could not read last network location", exception);
        }
        if (gps == null) {
            return network;
        }
        if (network == null) {
            return gps;
        }
        return gps.getTime() >= network.getTime() ? gps : network;
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException exception) {
                Log.w(TAG, "Could not remove location updates", exception);
            }
        }
        locationListener = null;
    }

    private void uploadLocation(Location location) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("device_id", deviceId);
            payload.put("latitude", location.getLatitude());
            payload.put("longitude", location.getLongitude());
            payload.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            payload.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
            payload.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
            payload.put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
            payload.put("client_time", System.currentTimeMillis());
        } catch (JSONException exception) {
            Log.w(TAG, "Could not build location payload", exception);
        }
        uploadJson("/api/location", payload);
        uploadedLocations += 1;
        updateNotification(statusText());
    }

    @SuppressLint("MissingPermission")
    private void startCameraCapture() {
        cameraThread = new HandlerThread("LinkViewCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        imageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                uploadRaw("image", "photo.jpg", "image/jpeg", bytes);
                uploadedPhotos += 1;
                updateNotification(statusText());
            } finally {
                image.close();
            }
        }, cameraHandler);

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = findBackCamera(cameraManager);
            if (cameraId == null) {
                Log.w(TAG, "No camera was found");
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.w(TAG, "Camera error " + error);
                    camera.close();
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException exception) {
            Log.w(TAG, "Could not open camera", exception);
        }
    }

    private String findBackCamera(CameraManager cameraManager) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        String[] cameraIds = cameraManager.getCameraIdList();
        return cameraIds.length > 0 ? cameraIds[0] : null;
    }

    private void createCameraSession() {
        if (cameraDevice == null || imageReader == null) {
            return;
        }
        try {
            cameraDevice.createCaptureSession(
                    Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            cameraSession = session;
                            schedulePhotoCapture(1000L);
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.w(TAG, "Camera session configuration failed");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException exception) {
            Log.w(TAG, "Could not create camera session", exception);
        }
    }

    private void schedulePhotoCapture(long delayMs) {
        if (!running || cameraHandler == null) {
            return;
        }
        cameraHandler.postDelayed(() -> {
            capturePhoto();
            schedulePhotoCapture(photoIntervalMs);
        }, delayMs);
    }

    private void capturePhoto() {
        if (!running || cameraDevice == null || cameraSession == null || imageReader == null) {
            return;
        }
        try {
            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.JPEG_ORIENTATION, 0);
            cameraSession.capture(builder.build(), null, cameraHandler);
        } catch (CameraAccessException | IllegalStateException exception) {
            Log.w(TAG, "Could not capture photo", exception);
        }
    }

    private void stopCameraCapture() {
        if (cameraHandler != null) {
            cameraHandler.removeCallbacksAndMessages(null);
        }
        if (cameraSession != null) {
            cameraSession.close();
            cameraSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void scheduleAudioRecording(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (running) {
                startAudioRecording();
            }
        }, delayMs);
    }

    @SuppressWarnings("deprecation")
    private void startAudioRecording() {
        if (!running || mediaRecorder != null) {
            return;
        }
        currentAudioFile = new File(getCacheDir(), "linkview-audio-" + System.currentTimeMillis() + ".m4a");
        try {
            mediaRecorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this)
                    : new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(96_000);
            mediaRecorder.setAudioSamplingRate(44_100);
            mediaRecorder.setOutputFile(currentAudioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            mainHandler.postDelayed(() -> stopAudioRecording(true), audioChunkMs);
        } catch (RuntimeException | IOException exception) {
            Log.w(TAG, "Could not start audio recording", exception);
            stopAudioRecording(false);
            scheduleAudioRecording(audioChunkMs);
        }
    }

    private void stopAudioRecording(boolean upload) {
        MediaRecorder recorder = mediaRecorder;
        File audioFile = currentAudioFile;
        mediaRecorder = null;
        currentAudioFile = null;

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not stop audio cleanly", exception);
            }
            recorder.reset();
            recorder.release();
        }

        if (upload && audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
            byte[] bytes = readFile(audioFile);
            if (bytes.length > 0) {
                uploadRaw("audio", "audio.m4a", "audio/mp4", bytes);
                uploadedAudioChunks += 1;
                updateNotification(statusText());
            }
        }
        if (audioFile != null && audioFile.exists() && !audioFile.delete()) {
            Log.w(TAG, "Could not delete temporary audio file");
        }
        if (running) {
            scheduleAudioRecording(500L);
        }
    }

    private byte[] readFile(File file) {
        try {
            java.io.FileInputStream input = new java.io.FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            input.close();
            return output.toByteArray();
        } catch (IOException exception) {
            Log.w(TAG, "Could not read temporary media", exception);
            return new byte[0];
        }
    }

    private void uploadJson(String path, JSONObject payload) {
        byte[] bytes = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        uploadExecutor.execute(() -> postBytes(path, "application/json; charset=utf-8", bytes));
    }

    private void uploadRaw(String kind, String filename, String contentType, byte[] bytes) {
        String path = "/api/media/raw?device_id=" + urlEncode(deviceId)
                + "&kind=" + urlEncode(kind)
                + "&filename=" + urlEncode(filename);
        uploadExecutor.execute(() -> postBytes(path, contentType, bytes));
    }

    private void postBytes(String path, String contentType, byte[] bytes) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(serverUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            if (!apiToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiToken);
            }
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.close();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Upload failed with HTTP " + code + " for " + path);
            }
        } catch (IOException exception) {
            Log.w(TAG, "Upload failed for " + path, exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String statusText() {
        return String.format(
                Locale.US,
                "Uploading: %d locations, %d audio chunks, %d photos",
                uploadedLocations,
                uploadedAudioChunks,
                uploadedPhotos
        );
    }

    private void startForegroundNotification(String text) {
        createNotificationChannel();
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                pendingIntentFlags()
        );

        Intent stopIntent = new Intent(this, CaptureUploadService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                pendingIntentFlags()
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_app_icon)
                .setContentTitle("LinkView capture upload is running")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(R.drawable.ic_app_icon, "Stop", stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "LinkView capture upload",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows when LinkView is uploading location, audio, and photos.");
        manager.createNotificationChannel(channel);
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private String sanitizeServerUrl(String value) {
        String trimmed = valueOrEmpty(value).trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String safeDeviceId(String value) {
        String trimmed = valueOrEmpty(value).trim();
        return trimmed.isEmpty() ? "linkview-device" : trimmed;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            return value;
        }
    }
}
