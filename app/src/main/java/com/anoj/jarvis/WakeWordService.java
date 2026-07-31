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
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;

import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineManager;

public class WakeWordService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.anoj.jarvis.START_WAKE_MODE";
    public static final String ACTION_STOP = "com.anoj.jarvis.STOP_WAKE_MODE";
    public static final String PREFS = "jarvis_prefs";
    public static final String KEY_ACTIVE = "wake_active";
    public static final String KEY_ACCESS = "picovoice_access_key";

    private static final String CHANNEL_ID = "jarvis_offline_wake";
    private static final int NOTIFICATION_ID = 2027;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PorcupineManager porcupineManager;
    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private TextToSpeech tts;
    private CameraManager cameraManager;
    private String flashCameraId;
    private boolean running;
    private boolean commandListening;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        tts = new TextToSpeech(this, this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        findFlashCamera();
        setupRecognizer();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopWakeMode(); return START_NOT_STICKY; }
        startForeground(NOTIFICATION_ID, buildNotification());
        running = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply();
        startOfflineWakeEngine();
        return START_STICKY;
    }

    private void startOfflineWakeEngine() {
        String accessKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ACCESS, "");
        if (accessKey == null || accessKey.trim().isEmpty()) {
            speak("Boss, pehle Picovoice AccessKey save kijiye.");
            stopWakeMode();
            return;
        }
        try {
            if (porcupineManager != null) { porcupineManager.delete(); porcupineManager = null; }
            porcupineManager = new PorcupineManager.Builder()
                    .setAccessKey(accessKey.trim())
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .setSensitivity(0.65f)
                    .build(this, keywordIndex -> onWakeWordDetected());
            porcupineManager.start();
        } catch (Exception e) {
            speak("Boss, offline wake engine start nahi hua. AccessKey check kijiye.");
            stopWakeMode();
        }
    }

    private void onWakeWordDetected() {
        handler.post(() -> {
            try { if (porcupineManager != null) porcupineManager.stop(); } catch (Exception ignored) { }
            speak("Main yahan hoon Boss.");
            handler.postDelayed(this::startCommandListening, 1200);
        });
    }

    private void setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
    }

    private void startCommandListening() {
        if (!running || recognizer == null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            resumeWakeEngine(); return;
        }
        commandListening = true;
        try { recognizer.startListening(recognizerIntent); }
        catch (Exception e) { commandListening = false; resumeWakeEngine(); }
    }

    private void resumeWakeEngine() {
        handler.postDelayed(() -> {
            if (!running || porcupineManager == null) return;
            try { porcupineManager.start(); } catch (Exception ignored) { }
        }, 350);
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace("टॉर्च", "torch").replace("फ्लैशलाइट", "torch")
                .replace("लाइट", "light").replace("चालू", "on").replace("बंद", "off")
                .replaceAll("\\s+", " ").trim();
    }

    private void handleCommand(String raw) {
        String c = normalize(raw);
        if ((c.contains("light") || c.contains("torch")) && (c.contains("off") || c.contains("band"))) {
            setTorch(false); speak("Ok Boss, light off kar diya.");
        } else if ((c.contains("light") || c.contains("torch")) && (c.contains("on") || c.contains("chalu") || !c.contains("off"))) {
            setTorch(true); speak("Ok Boss, light on kar diya.");
        } else if (c.contains("kaha") || c.contains("where") || c.contains("कहां")) {
            speak("Main yahan hoon Boss.");
        } else {
            speak("Ji Boss, command samajh nahi aayi.");
        }
        handler.postDelayed(this::resumeWakeEngine, 1300);
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_offline_reply");
    }

    private void findFlashCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean available = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(available) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    flashCameraId = id; return;
                }
            }
        } catch (Exception ignored) { }
    }

    private void setTorch(boolean enabled) {
        if (flashCameraId == null) findFlashCamera();
        if (flashCameraId == null) return;
        try { cameraManager.setTorchMode(flashCameraId, enabled); } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, WakeWordService.class); stopIntent.setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_jarvis).setContentTitle("JARVIS offline wake active")
                .setContentText("Say “Jarvis”, then speak a command")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "JARVIS Offline Wake", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void stopWakeMode() {
        running = false; commandListening = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        handler.removeCallbacksAndMessages(null);
        try { if (porcupineManager != null) { porcupineManager.stop(); porcupineManager.delete(); } } catch (Exception ignored) { }
        porcupineManager = null;
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    @Override public void onResults(android.os.Bundle results) {
        commandListening = false;
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) handleCommand(list.get(0)); else resumeWakeEngine();
    }
    @Override public void onError(int error) { commandListening = false; resumeWakeEngine(); }
    @Override public void onReadyForSpeech(android.os.Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(android.os.Bundle partialResults) { }
    @Override public void onEvent(int eventType, android.os.Bundle params) { }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) { tts.setLanguage(new Locale("hi", "IN")); tts.setSpeechRate(1.0f); }
    }
    @Override public void onDestroy() { stopWakeMode(); if (tts != null) { tts.stop(); tts.shutdown(); } super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
