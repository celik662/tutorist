package com.example.tutorist.ui.auth;

import android.widget.Toast;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.example.tutorist.R;
import com.example.tutorist.ui.student.StudentMainActivity;
import com.example.tutorist.ui.teacher.TeacherMainActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

public class LoginActivity extends AppCompatActivity {
    private EditText etEmail, etPass;
    private TextView tvMsg, tvBanner;
    private Button btnForgot, btnResendVerify;

    private Button btnLogin;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);
        auth = FirebaseAuth.getInstance();

        etEmail = findViewById(R.id.etEmail);
        etPass  = findViewById(R.id.etPass);
        tvMsg   = findViewById(R.id.tvMsg);
        tvBanner= findViewById(R.id.tvBanner);
        btnForgot = findViewById(R.id.btnForgot);
        btnLogin = findViewById(R.id.btnLogin);
        btnResendVerify = findViewById(R.id.btnResendVerify);

        // Kayıt veya splash'tan gelen bilgilendirme/basılı e-posta
        Intent i = getIntent();
        String prefill = i.getStringExtra("email");
        if (prefill != null) etEmail.setText(prefill);

        if (i.getBooleanExtra("verificationSent", false)) {
            tvBanner.setText("Doğrulama e-postası gönderildi. Lütfen posta kutunuzu kontrol edin.");
        } else if (i.getBooleanExtra("needsVerification", false)) {
            tvBanner.setText("Lütfen e-posta adresinizi doğrulayın ve tekrar giriş yapın.");
            btnResendVerify.setVisibility(View.VISIBLE);
        }
        String banner = i.getStringExtra("banner");
        if (banner != null && !banner.isEmpty()) tvBanner.setText(banner);

        btnLogin.setOnClickListener(v -> doLogin());
        btnForgot.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            startActivity(new Intent(this, ForgotPasswordActivity.class).putExtra("email", email));
        });
        btnResendVerify.setOnClickListener(v -> resendVerification());


        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(t -> {
                    // AppMessagingService.onNewToken çağrılmayabilir; token’ı burada da kaydedelim:
                    new com.example.tutorist.push.AppMessagingService().onNewToken(t);
                });
    }


    private void doLogin() {
        String email = etEmail.getText().toString().trim();
        String pass  = etPass.getText().toString();

        if (email.isEmpty() || pass.isEmpty()) {
            tvMsg.setText("E-posta ve şifre girin.");
            return;
        }

        // (İsteğe bağlı) Ağ kontrolü
        if (!com.example.tutorist.util.NetworkUtil.isOnline(this)) {
            tvMsg.setText("İnternet bağlantısı yok. Lütfen ağınızı kontrol edin.");
            return;
        }

        tvMsg.setText("Giriş yapılıyor…");
        tvBanner.setText("");
        btnResendVerify.setVisibility(View.GONE);
        btnLogin.setEnabled(false);

        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = auth.getCurrentUser();
                    if (user == null) {
                        tvMsg.setText("Kullanıcı bulunamadı.");
                        btnLogin.setEnabled(true);
                        return;
                    }

                    // E-posta doğrulama durumunu güncel görmek için
                    user.reload()
                            .addOnSuccessListener(v -> {
                                if (!user.isEmailVerified()) {
                                    tvMsg.setText("");
                                    tvBanner.setText("E-postanız doğrulanmamış. Lütfen posta kutunuzu kontrol edin.");
                                    btnResendVerify.setVisibility(View.VISIBLE);
                                    btnResendVerify.setOnClickListener(xx ->
                                            user.sendEmailVerification()
                                                    .addOnSuccessListener(x -> Toast.makeText(this, "Doğrulama e-postası gönderildi.", Toast.LENGTH_SHORT).show())
                                                    .addOnFailureListener(e -> Toast.makeText(this, "E-posta gönderilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show())
                                    );
                                    auth.signOut();
                                    btnLogin.setEnabled(true);
                                    return;
                                }

                                // E-posta doğrulandı → rol dokümanını oku ve yönlendir
                                FirebaseFirestore.getInstance()
                                        .collection("users").document(user.getUid())
                                        .get()
                                        .addOnSuccessListener(this::routeByRoleDoc) // mevcut metodunu kullanıyorsun
                                        .addOnFailureListener(e -> {
                                            tvBanner.setText("Rol bilgisi okunamadı: " + e.getMessage());
                                            auth.signOut();
                                            btnLogin.setEnabled(true);
                                        });
                            })
                            .addOnFailureListener(e -> {
                                tvMsg.setText("Hesap bilgisi yenilenemedi: " + e.getMessage());
                                auth.signOut();
                                btnLogin.setEnabled(true);
                            });
                })
                .addOnFailureListener(e -> {
                    tvMsg.setText(mapAuthError(e));
                    btnLogin.setEnabled(true);
                });
    }

    private String mapAuthError(Exception e) {
        if (e instanceof com.google.firebase.FirebaseNetworkException) {
            return "Ağ hatası: İnternet bağlantınızı kontrol edin.";
        }
        if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            return "E-posta veya şifre hatalı.";
        }
        if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            return "Böyle bir kullanıcı bulunamadı.";
        }
        if (e instanceof com.google.firebase.FirebaseTooManyRequestsException) {
            return "Çok fazla deneme yapıldı. Lütfen biraz sonra tekrar deneyin.";
        }
        return "Giriş başarısız: " + e.getMessage();
    }


    private void routeByRoleDoc(DocumentSnapshot doc) {
        String role = (doc.exists() && doc.getString("role") != null) ? doc.getString("role") : "";
        if ("teacher".equals(role)) {
            startActivity(new Intent(this, TeacherMainActivity.class));
            finish();
        } else if ("student".equals(role)) {
            startActivity(new Intent(this, StudentMainActivity.class));
            finish();
        } else {
            tvBanner.setText("Hesap rolü eksik veya geçersiz. Lütfen tekrar giriş yapın.");
            FirebaseAuth.getInstance().signOut();
        }
    }

    private void resendVerification() {
        String email = etEmail.getText().toString().trim();
        String pass  = etPass.getText().toString();
        if (email.isEmpty() || pass.isEmpty()) {
            tvMsg.setText("Yeniden göndermek için önce giriş bilgilerinizi girin.");
            return;
        }

        // Geçici giriş yap, doğrulama mailini gönder, sonra oturumu kapat.
        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(res -> {
                    FirebaseUser u = res.getUser();
                    if (u == null) { tvMsg.setText("Kullanıcı bulunamadı."); return; }

                    u.reload().addOnSuccessListener(r -> {
                        if (u.isEmailVerified()) {
                            tvMsg.setText("E-posta zaten doğrulanmış görünüyor.");
                            FirebaseAuth.getInstance().signOut();
                        } else {
                            u.sendEmailVerification()
                                    .addOnSuccessListener(v -> {
                                        tvMsg.setText("Doğrulama e-postası yeniden gönderildi.");
                                        btnResendVerify.setVisibility(View.VISIBLE);
                                        FirebaseAuth.getInstance().signOut();
                                    })
                                    .addOnFailureListener(e -> {
                                        tvMsg.setText("Gönderilemedi: " + e.getMessage());
                                        FirebaseAuth.getInstance().signOut();
                                    });
                        }
                    });
                })
                .addOnFailureListener(e -> tvMsg.setText("Oturum açılamadı: " + e.getMessage()));
    }
}
