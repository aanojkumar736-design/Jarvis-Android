package com.anoj.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.android.RecognitionListener;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WakeWordService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.anoj.jarvis.START_WAKE";
    public static final String ACTION_STOP = "com.anoj.jarvis.STOP_WAKE";
    public static final String PREFS = "jarvis_wake_prefs";
    public static final String KEY_ACTIVE = "wake_active";
    public static final String KEY_STATUS = "wake_status";
    public static final String KEY_DETAIL = "wake_detail";

    private static final String CHANNEL_ID = "jarvis_offline_vosk";
    private static final int NOTIFICATION_ID = 87;
    private static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextToSpeech tts;
    private Model model;
    private SpeechService speechService;
    private CameraManager cameraManager;
    private String flashCameraId;
    private boolean running;
    private boolean armed;
    private long armedUntil;
    private long lastWakeAt;

    private void setState(boolean active, String state, String detail) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, active)
                .putString(KEY_STATUS, state)
                .putString(KEY_DETAIL, detail)
                .apply();
        if (active) updateNotification(detail);
    }

    private boolean modelLooksValid(File modelDir) {
        return new File(modelDir, "conf/model.conf").exists()
                && new File(modelDir, "am/final.mdl").exists();
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        tts = new TextToSpeech(this, this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        findFlashCamera();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopWakeMode(); return START_NOT_STICKY; }
        startForeground(NOTIFICATION_ID, buildNotification("Preparing offline wake engine…"));
        running = true;
        setState(true, "PREPARING", "Offline engine taiyar ho raha hai");
        startOrDownloadModel();
        return START_STICKY;
    }

    private void startOrDownloadModel() {
        File modelDir = new File(getFilesDir(), MODEL_NAME);
        if (modelLooksValid(modelDir)) {
            setState(true, "LOADING", "Offline model load ho raha hai");
            executor.execute(() -> loadModel(modelDir));
            return;
        }

        // Purana adhoora/corrupt model ho to fresh retry.
        if (modelDir.exists()) deleteRecursively(modelDir);

        setState(true, "DOWNLOADING", "Model download ho raha hai (~40 MB)");
        executor.execute(() -> {
            File zip = new File(getCacheDir(), MODEL_NAME + ".zip");
            try {
                if (zip.exists()) zip.delete();
                downloadFile(MODEL_URL, zip);
                if (!running) return;

                setState(true, "EXTRACTING", "Offline model extract ho raha hai");
                unzip(zip, getFilesDir());
                zip.delete();

                if (!modelLooksValid(modelDir)) {
                    throw new IllegalStateException("Model files incomplete");
                }
                loadModel(modelDir);
            } catch (Exception e) {
                if (zip.exists()) zip.delete();
                deleteRecursively(modelDir);
                String message = e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "unknown error" : e.getMessage());
                setState(false, "ERROR", message);
                handler.post(() -> speak("Boss, offline model ready nahi hua. App me error status dekhiye."));
                running = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
    }

    private void loadModel(File modelDir) {
        try {
            model = new Model(modelDir.getAbsolutePath());
            Recognizer recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            handler.post(() -> {
                if (!running) return;
                setState(true, "LISTENING", "Offline listening active — boliye: Jarvis");
                speechService.startListening(this);
                speak("Offline wake mode ready hai Boss.");
            });
        } catch (Exception e) {
            String message = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "engine load error" : e.getMessage());
            setState(false, "ERROR", message);
            handler.post(() -> speak("Boss, offline engine load nahi hua."));
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void downloadFile(String urlText, File out) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(20000); conn.setReadTimeout(60000); conn.connect();
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IllegalStateException("HTTP " + conn.getResponseCode());
        try (InputStream in = new BufferedInputStream(conn.getInputStream()); BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) { if (!running) throw new InterruptedException(); bos.write(buffer, 0, n); }
        } finally { conn.disconnect(); }
    }

    private void unzip(File zipFile, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry; byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File target = new File(destination, entry.getName());
                if (!target.getCanonicalPath().startsWith(root)) throw new SecurityException("Bad zip path");
                if (entry.isDirectory()) { if (!target.exists() && !target.mkdirs()) throw new IllegalStateException("mkdir failed"); }
                else {
                    File parent = target.getParentFile(); if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("mkdir failed");
                    try (FileOutputStream fos = new FileOutputStream(target)) { int n; while ((n = zis.read(buffer)) > 0) fos.write(buffer, 0, n); }
                }
                zis.closeEntry();
            }
        }
    }

    private String textFromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String text = o.optString("text", "");
            if (text.isEmpty()) text = o.optString("partial", "");
            return text.toLowerCase(Locale.ROOT).trim();
        } catch (Exception e) { return ""; }
    }

    private void processText(String text) {
        if (!running || text.isEmpty()) return;
        long now = System.currentTimeMillis();
        setState(true, "LISTENING", "Suna: " + text);
        boolean hasJarvis = text.contains("jarvis")
                || text.contains("jervis")
                || text.contains("service")
                || text.contains("travis")
                || text.contains("harvest");
        if (hasJarvis && now - lastWakeAt > 1800) {
            lastWakeAt = now;
            armed = true;
            armedUntil = now + 9000;
            if (hasLightOn(text)) { executeLight(true); return; }
            if (hasLightOff(text)) { executeLight(false); return; }
            pauseAndSpeak("Main yahan hoon Boss.");
            return;
        }
        if (armed && now <= armedUntil) {
            if (hasLightOff(text)) { executeLight(false); return; }
            if (hasLightOn(text)) { executeLight(true); return; }
            if (text.contains("where") || text.contains("kaha") || text.contains("kahan")) {
                armed = false; pauseAndSpeak("Main yahan hoon Boss.");
            }
        } else if (now > armedUntil) armed = false;
    }

    private boolean hasLightOn(String t) { return (t.contains("light") || t.contains("torch")) && (t.contains("on") || t.contains("open")); }
    private boolean hasLightOff(String t) { return (t.contains("light") || t.contains("torch")) && (t.contains("off") || t.contains("close")); }

    private void executeLight(boolean on) {
        armed = false;
        setTorch(on);
        pauseAndSpeak(on ? "Ok Boss, light on kar diya." : "Ok Boss, light off kar diya.");
    }

    private void pauseAndSpeak(String text) {
        try { if (speechService != null) speechService.stop(); } catch (Exception ignored) { }
        speak(text);
        handler.postDelayed(() -> {
            if (running && speechService != null) {
                try { speechService.startListening(this); } catch (Exception ignored) { }
            }
        }, 1800);
    }

    private void speak(String text) { if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_vosk_reply"); }

    private void findFlashCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean available = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(available) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) { flashCameraId = id; return; }
            }
        } catch (Exception ignored) { }
    }

    private void setTorch(boolean enabled) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (flashCameraId == null) findFlashCamera();
        if (flashCameraId == null) return;
        try { cameraManager.setTorchMode(flashCameraId, enabled); } catch (Exception ignored) { }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, WakeWordService.class); stopIntent.setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_jarvis).setContentTitle("JARVIS offline wake active").setContentText(text)
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    private void updateNotification(String text) { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text)); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "JARVIS Offline Wake", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void stopWakeMode() {
        running = false; armed = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, false)
                .putString(KEY_STATUS, "STOPPED")
                .putString(KEY_DETAIL, "Offline Wake Mode band hai")
                .apply();
        handler.removeCallbacksAndMessages(null);
        try { if (speechService != null) { speechService.stop(); speechService.shutdown(); } } catch (Exception ignored) { }
        speechService = null;
        try { if (model != null) model.close(); } catch (Exception ignored) { }
        model = null;
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    @Override public void onPartialResult(String hypothesis) { processText(textFromJson(hypothesis)); }
    @Override public void onResult(String hypothesis) { processText(textFromJson(hypothesis)); }
    @Override public void onFinalResult(String hypothesis) { processText(textFromJson(hypothesis)); }
    @Override public void onError(Exception exception) {
        if (!running) return;
        String message = exception == null ? "unknown"
                : exception.getClass().getSimpleName() + ": " + exception.getMessage();
        setState(false, "ERROR", "Mic/Vosk error: " + message);
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onTimeout() { if (running && speechService != null) try { speechService.startListening(this); } catch (Exception ignored) { } }
    @Override public void onInit(int status) { if (status == TextToSpeech.SUCCESS) { tts.setLanguage(new Locale("hi", "IN")); tts.setSpeechRate(1.0f); } }
    @Override public void onDestroy() { stopWakeMode(); executor.shutdownNow(); if (tts != null) { tts.stop(); tts.shutdown(); } super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
