package in.maddybgmistore.admin;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.Executor;

public class LockActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "MBSAdminPrefs";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_PIN_SET = "pin_set";
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 30_000L;

    private SharedPreferences prefs;
    private EditText etPin;
    private TextView tvStatus;
    private TextView tvTitle;
    private Button btnUnlock;
    private Button btnBiometric;
    private View cardPin;

    private boolean isPinSetup = false;
    private String pendingNewPin = null;
    private int failedAttempts = 0;
    private boolean isLockedOut = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Security: prevent screenshots
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_lock);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        etPin = findViewById(R.id.et_pin);
        tvStatus = findViewById(R.id.tv_status);
        tvTitle = findViewById(R.id.tv_lock_title);
        btnUnlock = findViewById(R.id.btn_unlock);
        btnBiometric = findViewById(R.id.btn_biometric);
        cardPin = findViewById(R.id.card_pin);

        isPinSetup = prefs.getBoolean(KEY_PIN_SET, false);

        if (!isPinSetup) {
            tvTitle.setText("Create Admin PIN");
            tvStatus.setText("Set a 4–6 digit PIN to secure this app");
            btnUnlock.setText("Set PIN");
            btnBiometric.setVisibility(View.GONE);
        } else {
            tvTitle.setText("Admin Login");
            tvStatus.setText("Enter your PIN to access admin panel");
            btnUnlock.setText("Unlock");
            setupBiometricButton();
        }

        etPin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                int len = s.toString().trim().length();
                if (len >= 4 && len <= 6) {
                    btnUnlock.setAlpha(1.0f);
                    btnUnlock.setEnabled(true);
                } else {
                    btnUnlock.setAlpha(0.5f);
                    btnUnlock.setEnabled(false);
                }
            }
        });

        btnUnlock.setAlpha(0.5f);
        btnUnlock.setEnabled(false);

        btnUnlock.setOnClickListener(v -> handlePinAction());
        etPin.setOnEditorActionListener((v, actionId, event) -> {
            handlePinAction();
            return true;
        });
    }

    private void handlePinAction() {
        if (isLockedOut) {
            Toast.makeText(this, "Too many attempts. Please wait.", Toast.LENGTH_SHORT).show();
            return;
        }

        String pin = etPin.getText().toString().trim();
        if (pin.length() < 4 || pin.length() > 6) {
            tvStatus.setText("PIN must be 4–6 digits");
            return;
        }

        if (!isPinSetup) {
            // First time: setup flow
            if (pendingNewPin == null) {
                pendingNewPin = pin;
                etPin.setText("");
                tvStatus.setText("Confirm your PIN");
                btnUnlock.setText("Confirm");
            } else {
                if (pendingNewPin.equals(pin)) {
                    savePin(pin);
                    isPinSetup = true;
                    proceedToMain();
                } else {
                    pendingNewPin = null;
                    etPin.setText("");
                    tvStatus.setText("PINs didn't match. Start over.");
                    btnUnlock.setText("Set PIN");
                }
            }
        } else {
            // Verify existing PIN
            if (verifyPin(pin)) {
                failedAttempts = 0;
                proceedToMain();
            } else {
                failedAttempts++;
                etPin.setText("");
                if (failedAttempts >= MAX_ATTEMPTS) {
                    triggerLockout();
                } else {
                    int remaining = MAX_ATTEMPTS - failedAttempts;
                    tvStatus.setText("Wrong PIN. " + remaining + " attempt(s) left.");
                }
            }
        }
    }

    private void triggerLockout() {
        isLockedOut = true;
        btnUnlock.setEnabled(false);
        btnBiometric.setEnabled(false);
        tvStatus.setText("Too many failed attempts. Locked for 30 seconds.");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isLockedOut = false;
            failedAttempts = 0;
            btnUnlock.setEnabled(true);
            btnBiometric.setEnabled(true);
            tvStatus.setText("Enter your PIN to access admin panel");
        }, LOCKOUT_DURATION_MS);
    }

    private void savePin(String pin) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] saltBytes = new byte[16];
            random.nextBytes(saltBytes);
            String salt = bytesToHex(saltBytes);
            String hash = hashPin(pin, salt);
            prefs.edit()
                    .putString(KEY_PIN_HASH, hash)
                    .putString(KEY_PIN_SALT, salt)
                    .putBoolean(KEY_PIN_SET, true)
                    .apply();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving PIN", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean verifyPin(String pin) {
        try {
            String salt = prefs.getString(KEY_PIN_SALT, "");
            String storedHash = prefs.getString(KEY_PIN_HASH, "");
            String inputHash = hashPin(pin, salt);
            return storedHash.equals(inputHash);
        } catch (Exception e) {
            return false;
        }
    }

    private String hashPin(String pin, String salt) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String saltedPin = salt + pin + "MBS_ADMIN_SALT_2024";
        byte[] hashBytes = digest.digest(saltedPin.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hashBytes);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void setupBiometricButton() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuth = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            btnBiometric.setVisibility(View.VISIBLE);
            btnBiometric.setOnClickListener(v -> showBiometricPrompt());
            // Auto-trigger biometric on start
            new Handler(Looper.getMainLooper()).postDelayed(this::showBiometricPrompt, 400);
        } else {
            btnBiometric.setVisibility(View.GONE);
        }
    }

    private void showBiometricPrompt() {
        if (isLockedOut) return;

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        proceedToMain();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        tvStatus.setText("Fingerprint not recognized. Try PIN.");
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // User cancelled or error — just show PIN
                        tvStatus.setText("Enter your PIN to access admin panel");
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("MADDY BGMI STORE")
                .setSubtitle("Admin Panel Access")
                .setDescription("Use fingerprint to unlock")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use PIN")
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void proceedToMain() {
        Intent intent = new Intent(LockActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
