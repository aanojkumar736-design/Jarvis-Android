package com.anoj.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_SPEECH = 101;
    private static final int REQ_PERMISSIONS = 102;

    private TextToSpeech tts;
    private EditText input;
    private LinearLayout chatContainer;
    private ScrollView scroll;
    private TextView status;
    private CameraManager cameraManager;
    private String flashCameraId;
    private boolean torchOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.anoj.jarvis.R.layout.activity_main);

        input = findViewById(R.id.input);
        chatContainer = findViewById(R.id.chatContainer);
        scroll = findViewById(R.id.scroll);
        status = findViewById(R.id.status);
        Button micButton = findViewById(R.id.micButton);
        Button sendButton = findViewById(R.id.sendButton);
        Button wakeButton = findViewById(R.id.wakeButton);

        tts = new TextToSpeech(this, this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        findFlashCamera();

        requestPermissionsIfNeeded();

        addBot("Namaste Boss. Main JARVIS hoon. Aap mujhe command de sakte hain.");

        sendButton.setOnClickListener(v -> submit(input.getText().toString()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit(input.getText().toString());
            return true;
        });
        micButton.setOnClickListener(v -> startVoiceRecognition());
        updateWakeButton(wakeButton);
        wakeButton.setOnClickListener(v -> {
            boolean active = getSharedPreferences(WakeWordService.PREFS, MODE_PRIVATE)
                    .getBoolean(WakeWordService.KEY_ACTIVE, false);
            Intent serviceIntent = new Intent(this, WakeWordService.class);
            serviceIntent.setAction(active ? WakeWordService.ACTION_STOP : WakeWordService.ACTION_START);
            if (active) {
                startService(serviceIntent);
                addBot("Background Wake Word Mode band ho gaya Boss.");
            } else {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSIONS);
                    addBot("Boss, microphone permission allow karke Wake Mode phir dabaiye.");
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
                else startService(serviceIntent);
                addBot("Background Wake Word Mode chalu hai Boss. App minimize karke boliye: Jarvis, tum kaha ho.");
            }
            new android.os.Handler(getMainLooper()).postDelayed(() -> updateWakeButton(wakeButton), 500);
        });
    }

    private void updateWakeButton(Button button) {
        boolean active = getSharedPreferences(WakeWordService.PREFS, MODE_PRIVATE)
                .getBoolean(WakeWordService.KEY_ACTIVE, false);
        button.setText(active ? "STOP WAKE MODE" : "START WAKE MODE");
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA);
        if (!needed.isEmpty())
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
    }

    private void startVoiceRecognition() {
        status.setText("LISTENING");
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Boss, boliye...");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            addBot("Boss, phone me speech recognition service nahi mili.");
            status.setText("ONLINE");
        }
    }

    private void submit(String raw) {
        String message = raw == null ? "" : raw.trim();
        if (message.isEmpty()) return;
        input.setText("");
        addUser(message);
        executeCommand(message);
    }

    private String normalize(String s) {
        String t = s.toLowerCase(Locale.ROOT).trim();
        t = t.replace("यूट्यूब", "youtube")
             .replace("यू ट्यूब", "youtube")
             .replace("गूगल", "google")
             .replace("व्हाट्सएप", "whatsapp")
             .replace("व्हाट्सऐप", "whatsapp")
             .replace("वॉट्सऐप", "whatsapp")
             .replace("वाट्सएप", "whatsapp")
             .replace("कैमरा", "camera")
             .replace("टॉर्च", "torch")
             .replace("फ्लैशलाइट", "torch")
             .replace("फोन", "phone")
             .replace("कॉल", "call")
             .replace("खोलो", "open")
             .replace("खोल", "open")
             .replace("चालू", "on")
             .replace("बंद", "off");
        return t.replaceAll("\\s+", " ");
    }

    private void executeCommand(String raw) {
        String c = normalize(raw);

        if (c.contains("youtube")) {
            replyThen("Ok Boss, YouTube khol raha hoon.", this::openYouTube);
        } else if (c.contains("whatsapp")) {
            replyThen("Ok Boss, WhatsApp khol raha hoon.", this::openWhatsApp);
        } else if (c.contains("camera")) {
            replyThen("Ok Boss, Camera khol raha hoon.", this::openCamera);
        } else if (c.contains("google")) {
            replyThen("Ok Boss, Google khol raha hoon.", this::openGoogle);
        } else if (c.contains("torch") && (c.contains("off") || c.contains("band"))) {
            setTorch(false);
        } else if (c.contains("torch")) {
            setTorch(true);
        } else if (c.startsWith("call ") || c.startsWith("phone ") || c.contains("dialer")) {
            openDialer(extractPhone(raw));
        } else if (c.contains("clear chat") || c.contains("chat clear")) {
            chatContainer.removeAllViews();
            addBot("Chat clear ho gaya Boss.");
        } else if (c.contains("hello") || c.contains("hi") || c.contains("namaste")) {
            speakAndShow("Namaste Boss. Main ready hoon.");
        } else {
            speakAndShow("Boss, abhi main phone commands chala sakta hoon: YouTube, Google, WhatsApp, Camera, Torch aur Call.");
        }
    }

    private void replyThen(String reply, Runnable action) {
        addBot(reply);
        speak(reply, action);
    }

    private void speakAndShow(String reply) {
        addBot(reply);
        speak(reply, null);
    }

    private void speak(String text, Runnable after) {
        status.setText("SPEAKING");
        if (tts == null) {
            status.setText("ONLINE");
            if (after != null) after.run();
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply");
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            status.setText("ONLINE");
            if (after != null) after.run();
        }, Math.max(1800, text.length() * 70L));
    }

    private void openYouTube() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.google.android.youtube");
        if (launch != null) startActivity(launch);
        else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")));
    }

    private void openWhatsApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.whatsapp");
        if (launch != null) startActivity(launch);
        else toast("WhatsApp app nahi mila Boss.");
    }

    private void openGoogle() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.google.android.googlequicksearchbox");
        if (launch != null) startActivity(launch);
        else {
            Intent chrome = getPackageManager().getLaunchIntentForPackage("com.android.chrome");
            if (chrome != null) startActivity(chrome);
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")));
        }
    }

    private void openCamera() {
        Intent camera = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        try {
            startActivity(camera);
        } catch (ActivityNotFoundException e) {
            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            try { startActivity(capture); }
            catch (ActivityNotFoundException ex) { toast("Camera app nahi mila Boss."); }
        }
    }

    private void openDialer(String number) {
        Intent i = new Intent(Intent.ACTION_DIAL);
        if (!number.isEmpty()) i.setData(Uri.parse("tel:" + number));
        startActivity(i);
        speakAndShow(number.isEmpty() ? "Ok Boss, dialer khol raha hoon." : "Ok Boss, number dialer me daal raha hoon.");
    }

    private String extractPhone(String raw) {
        return raw.replaceAll("[^0-9+]", "");
    }

    private void findFlashCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean flash = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    flashCameraId = id;
                    break;
                }
            }
        } catch (CameraAccessException ignored) {}
    }

    private void setTorch(boolean enable) {
        if (flashCameraId == null) {
            speakAndShow("Boss, is phone me torch access nahi mila.");
            return;
        }
        try {
            cameraManager.setTorchMode(flashCameraId, enable);
            torchOn = enable;
            speakAndShow(enable ? "Ok Boss, torch on kar diya." : "Ok Boss, torch off kar diya.");
        } catch (Exception e) {
            speakAndShow("Boss, torch control nahi ho paya.");
        }
    }

    private void addUser(String text) { addBubble(text, true); }
    private void addBot(String text) { addBubble(text, false); }

    private void addBubble(String text, boolean user) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(18);
        tv.setPadding(20, 16, 20, 16);
        tv.setBackgroundColor(Color.parseColor(user ? "#0C5066" : "#102630"));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                (int)(getResources().getDisplayMetrics().widthPixels * 0.78),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.gravity = user ? Gravity.END : Gravity.START;
        p.setMargins(0, 8, 0, 8);
        tv.setLayoutParams(p);
        chatContainer.addView(tv);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        addBot(text);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        status.setText("ONLINE");
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) submit(results.get(0));
        }
    }

    @Override
    public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("hi", "IN"));
            tts.setSpeechRate(1.0f);
            tts.setPitch(1.0f);
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (torchOn) setTorch(false);
        super.onDestroy();
    }
}
