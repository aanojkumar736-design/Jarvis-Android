package com.anoj.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FloatingAssistantService extends Service {
    public static final String ACTION_START = "com.anoj.jarvis.FLOAT_START";
    public static final String ACTION_STOP = "com.anoj.jarvis.FLOAT_STOP";
    private static final String CHANNEL_ID = "jarvis_float_channel";
    private static final int NOTIFICATION_ID = 27;

    private WindowManager windowManager;
    private TextView bubble;
    private WindowManager.LayoutParams params;
    private SpeechRecognizer recognizer;
    private CameraManager cameraManager;
    private String flashCameraId;
    private boolean listening;
    private TextToSpeech tts;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        findFlashCamera();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("hi", "IN"));
                tts.setSpeechRate(1.0f);
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (Settings.canDrawOverlays(this)) showBubble();
        else {
            Toast.makeText(this, "Boss, Display over other apps permission allow kijiye.", Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_STICKY;
    }

    private void showBubble() {
        if (bubble != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new TextView(this);
        bubble.setText("J");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(10f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackgroundResource(R.drawable.bg_float_bubble);
        bubble.setElevation(10f);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(dp(24), dp(24), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = getSharedPreferences("float_pos", MODE_PRIVATE).getInt("x", 920);
        params.y = getSharedPreferences("float_pos", MODE_PRIVATE).getInt("y", 760);
        windowManager.addView(bubble, params);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            long downAt;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = params.x; startY = params.y; downAt = System.currentTimeMillis(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downX);
                        int dy = (int) (event.getRawY() - downY);
                        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true;
                        params.x = startX + dx; params.y = startY + dy;
                        windowManager.updateViewLayout(bubble, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        getSharedPreferences("float_pos", MODE_PRIVATE).edit()
                                .putInt("x", params.x).putInt("y", params.y).apply();
                        if (!moved && System.currentTimeMillis() - downAt < 450) toggleListening();
                        return true;
                }
                return false;
            }
        });
    }

    private void toggleListening() {
        if (listening) {
            stopListening();
        } else {
            startListening();
        }
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition available nahi hai Boss.", Toast.LENGTH_SHORT).show();
            return;
        }
        stopRecognizerOnly();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { listening = true; setBubbleMic(); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                Toast.makeText(FloatingAssistantService.this,
                        "Mic error " + error + " Boss", Toast.LENGTH_SHORT).show();
                resetBubble();
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String command = matches == null || matches.isEmpty() ? "" : matches.get(0);
                resetBubble();
                if (!command.trim().isEmpty()) executeCommand(command);
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizer.startListening(i);
        listening = true;
        setBubbleMic();
    }

    private void stopListening() {
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
        }
        resetBubble();
    }

    private void setBubbleMic() {
        if (bubble != null) {
            bubble.setText("🎤");
            bubble.setTextSize(10f);
            bubble.setBackgroundResource(R.drawable.bg_float_listening);
        }
    }

    private void resetBubble() {
        listening = false;
        if (bubble != null) {
            bubble.setText("J");
            bubble.setTextSize(10f);
            bubble.setBackgroundResource(R.drawable.bg_float_bubble);
        }
        stopRecognizerOnly();
    }

    private void executeCommand(String raw) {
        String c = normalize(raw);
        Toast.makeText(this, "Boss: " + raw, Toast.LENGTH_SHORT).show();

        if ((c.contains("torch") || c.contains("light")) && (c.contains("off") || c.contains("band"))) {
            setTorch(false); return;
        }
        if ((c.contains("torch") || c.contains("light")) && (c.contains("on") || c.contains("chalu") || c.contains("open"))) {
            setTorch(true); return;
        }
        if (c.contains("home") || c.contains("ghar") || c.contains("close") || c.contains("band karo")) {
            goHome(); return;
        }
        if (c.contains("youtube") && (c.contains("search") || c.contains("chalao") || c.contains("play"))) {
            String q = cleanQuery(c, "youtube", "search", "chalao", "play", "par", "pe");
            openUrl("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(q)); return;
        }
        if ((c.contains("google") || c.contains("search")) && !(c.endsWith("open") || c.endsWith("kholo"))) {
            String q = cleanQuery(c, "google", "search", "karo", "par", "pe");
            openUrl("https://www.google.com/search?q=" + android.net.Uri.encode(q)); return;
        }

        String app = extractAppName(c);
        if (!app.isEmpty() && openInstalledApp(app)) return;

        if (c.contains("youtube")) { openPackageOrUrl("com.google.android.youtube", "https://www.youtube.com"); return; }
        if (c.contains("whatsapp")) { openPackageOrUrl("com.whatsapp", "https://wa.me/"); return; }
        if (c.contains("chrome")) { openPackageOrUrl("com.android.chrome", "https://www.google.com"); return; }
        if (c.contains("chatgpt")) { if (!openInstalledApp("chatgpt")) openUrl("https://chatgpt.com"); return; }
        if (c.contains("camera")) {
            Intent cam = new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            cam.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); safeStart(cam); return;
        }
        speakThenSearch(raw);
    }


    private void speakThenSearch(String raw) {
        String message = "Boss, ye command direct available nahi hai. Google par search kar raha hoon.";
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        if (tts != null) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis_fallback");
            handler.postDelayed(() -> openUrl("https://www.google.com/search?q=" + android.net.Uri.encode(raw)), 2200);
        } else {
            openUrl("https://www.google.com/search?q=" + android.net.Uri.encode(raw));
        }
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).trim()
                .replace("यूट्यूब", "youtube").replace("यू ट्यूब", "youtube")
                .replace("गूगल", "google").replace("व्हाट्सएप", "whatsapp")
                .replace("व्हाट्सऐप", "whatsapp").replace("वॉट्सऐप", "whatsapp")
                .replace("क्रोम", "chrome").replace("कैमरा", "camera")
                .replace("टॉर्च", "torch").replace("लाइट", "light")
                .replace("खोलो", "open").replace("खोल", "open")
                .replace("चालू", "on").replace("बंद", "off")
                .replaceAll("\\s+", " ");
    }

    private String extractAppName(String c) {
        String t = c.replace("please", "").replace("jarvis", "").trim();
        if (t.contains(" open")) return t.substring(0, t.indexOf(" open")).trim();
        if (t.startsWith("open ")) return t.substring(5).trim();
        if (t.contains(" kholo")) return t.substring(0, t.indexOf(" kholo")).trim();
        return "";
    }

    private boolean openInstalledApp(String spokenName) {
        String target = spokenName.replaceAll("[^a-z0-9 ]", "").trim();
        if (target.isEmpty()) return false;
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN, null);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo r : apps) {
            String label = r.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            String clean = label.replaceAll("[^a-z0-9 ]", "").trim();
            int score = 0;
            if (clean.equals(target)) score = 100;
            else if (clean.contains(target) || target.contains(clean)) score = 70;
            else {
                for (String word : target.split(" ")) if (word.length() > 2 && clean.contains(word)) score += 15;
            }
            if (score > bestScore) { bestScore = score; best = r; }
        }
        if (best == null || bestScore < 30) return false;
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(launch);
        return true;
    }

    private String cleanQuery(String c, String... words) {
        String q = c;
        for (String w : words) q = q.replace(w, " ");
        q = q.replace("open", " ").replace("khol", " ").replaceAll("\\s+", " ").trim();
        return q.isEmpty() ? c : q;
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(home);
    }

    private void openPackageOrUrl(String pkg, String url) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); safeStart(launch); }
        else openUrl(url);
    }

    private void openUrl(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "Boss, ye action open nahi hua.", Toast.LENGTH_SHORT).show(); }
    }

    private void findFlashCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean available = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(available) && facing != null &&
                        facing == CameraCharacteristics.LENS_FACING_BACK) {
                    flashCameraId = id; break;
                }
            }
        } catch (Exception ignored) { }
    }

    private void setTorch(boolean on) {
        try {
            if (flashCameraId == null) findFlashCamera();
            if (flashCameraId != null) {
                cameraManager.setTorchMode(flashCameraId, on);
                Toast.makeText(this, on ? "Ok Boss, light on." : "Ok Boss, light off.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Boss, torch control nahi hua.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (Build.VERSION.SDK_INT >= 34) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, FloatingAssistantService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("JARVIS Floating Assistant")
                .setContentText("Tiny J button active — tap karke command boliye")
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "STOP", stopPi).build())
                .setOngoing(true).build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID,
                    "JARVIS Floating Assistant", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Floating J button ko active rakhta hai");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void stopRecognizerOnly() {
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    @Override public void onDestroy() {
        stopRecognizerOnly();
        if (bubble != null && windowManager != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) { }
        }
        bubble = null;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
