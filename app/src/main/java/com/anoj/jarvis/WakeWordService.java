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
import android.hardware.camera2.CameraAccessException;
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

public class WakeWordService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.anoj.jarvis.START_WAKE_MODE";
    public static final String ACTION_STOP = "com.anoj.jarvis.STOP_WAKE_MODE";
    public static final String PREFS = "jarvis_prefs";
    public static final String KEY_ACTIVE = "wake_active";

    private static final String CHANNEL_ID = "jarvis_wake_channel";
    private static final int NOTIFICATION_ID = 2026;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private TextToSpeech tts;
    private boolean running;
    private boolean speaking;
    private CameraManager cameraManager;
    private String flashCameraId;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        tts = new TextToSpeech(this, this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        findFlashCamera();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopWakeMode();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        running = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply();
        setupRecognizer();
        startListeningSoon(400);
        return START_STICKY;
    }

    private void setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
    }

    private void startListeningSoon(long delayMs) {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::startListening, delayMs);
    }

    private void startListening() {
        if (!running || speaking || recognizer == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            recognizer.cancel();
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            setupRecognizer();
            startListeningSoon(1200);
        }
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace("जर्विस", "jarvis")
                .replace("जारविस", "jarvis")
                .replace("जार्विस", "jarvis")
                .replace("टॉर्च", "torch")
                .replace("फ्लैशलाइट", "torch")
                .replace("लाइट", "light")
                .replace("चालू", "on")
                .replace("बंद", "off")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void handleSpeech(String raw) {
        String c = normalize(raw);
        if (!c.contains("jarvis")) return;

        if ((c.contains("light") || c.contains("torch")) && (c.contains("off") || c.contains("band"))) {
            setTorch(false);
            speak("Ok Boss, light off kar diya.");
        } else if (c.contains("light") || c.contains("torch")) {
            setTorch(true);
            speak("Ok Boss, light on kar diya.");
        } else if (c.contains("kaha") || c.contains("where") || c.contains("हो कहाँ") || c.contains("कहां")) {
            speak("Main yahan hoon Boss.");
        } else {
            speak("Ji Boss, main sun raha hoon.");
        }
    }

    private void speak(String text) {
        speaking = true;
        if (recognizer != null) recognizer.cancel();
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wake_reply");
        }
        handler.postDelayed(() -> {
            speaking = false;
            startListeningSoon(350);
        }, Math.max(1500L, text.length() * 65L));
    }

    private void findFlashCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean available = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(available) &&
                        (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    flashCameraId = id;
                    return;
                }
            }
        } catch (CameraAccessException ignored) { }
    }

    private void setTorch(boolean enabled) {
        if (flashCameraId == null) return;
        try {
            cameraManager.setTorchMode(flashCameraId, enabled);
        } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, WakeWordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("JARVIS is listening")
                .setContentText("Say: Jarvis, tum kaha ho / Jarvis light on")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "JARVIS Wake Mode", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps JARVIS listening in the background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void stopWakeMode() {
        running = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onReadyForSpeech(android.os.Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onError(int error) { if (running && !speaking) startListeningSoon(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1000 : 450); }
    @Override public void onResults(android.os.Bundle results) {
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) handleSpeech(list.get(0));
        if (running && !speaking) startListeningSoon(350);
    }
    @Override public void onPartialResults(android.os.Bundle partialResults) { }
    @Override public void onEvent(int eventType, android.os.Bundle params) { }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("hi", "IN"));
            tts.setSpeechRate(1.0f);
            tts.setPitch(1.0f);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
