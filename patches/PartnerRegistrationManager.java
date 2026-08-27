package com.teslalyrics.app;

import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Registers the developer application with Tesla Fleet API without sending credentials to any third-party server. */
public final class PartnerRegistrationManager {
    public interface Callback { void done(boolean ok, String message); }
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final SettingsStore settings;
    private final OkHttpClient http = new OkHttpClient();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    public PartnerRegistrationManager(SettingsStore settings) { this.settings = settings; }

    public void register(Callback cb) {
        String clientId = settings.clientId().trim();
        String secret = settings.clientSecret();
        String domain = cleanDomain(settings.developerDomain());
        String audience = settings.audience().trim();
        if (clientId.isEmpty() || secret.isEmpty()) { cb.done(false, "请先保存 Client ID / Client Secret"); return; }
        if (domain.isEmpty()) { cb.done(false, "请先填写 Developer Domain"); return; }
        if (audience.isEmpty()) { cb.done(false, "缺少 Fleet API Audience"); return; }
        exec.submit(() -> doRegister(clientId, secret, domain, audience, cb));
    }

    public void verify(Callback cb) {
        String clientId = settings.clientId().trim();
        String secret = settings.clientSecret();
        String domain = cleanDomain(settings.developerDomain());
        String audience = settings.audience().trim();
        if (clientId.isEmpty() || secret.isEmpty() || domain.isEmpty() || audience.isEmpty()) { cb.done(false, "请先保存 Tesla Developer 设置"); return; }
        exec.submit(() -> {
            try {
                String token = partnerToken(clientId, secret, audience);
                if (token.isEmpty()) { cb.done(false, "无法获取 Partner Token"); return; }
                Request req = new Request.Builder().url(audience + "/api/1/partner_accounts/public_key?domain=" + domain)
                        .header("Authorization", "Bearer " + token).get().build();
                try (Response r = http.newCall(req).execute()) {
                    String text = r.body() == null ? "" : r.body().string();
                    if (!r.isSuccessful()) { cb.done(false, "Partner 验证 HTTP " + r.code() + compact(text)); return; }
                    cb.done(true, "Partner 已注册，Tesla 已识别 Public Key");
                }
            } catch (Exception e) { cb.done(false, "Partner 验证失败: " + e.getClass().getSimpleName()); }
        });
    }

    private void doRegister(String clientId, String secret, String domain, String audience, Callback cb) {
        try {
            String token = partnerToken(clientId, secret, audience);
            if (token.isEmpty()) { cb.done(false, "Partner Token 获取失败，请检查 Client Secret"); return; }
            JSONObject body = new JSONObject().put("domain", domain);
            Request req = new Request.Builder().url(audience + "/api/1/partner_accounts")
                    .header("Authorization", "Bearer " + token)
                    .post(RequestBody.create(body.toString(), JSON)).build();
            try (Response r = http.newCall(req).execute()) {
                String text = r.body() == null ? "" : r.body().string();
                if (r.isSuccessful()) { cb.done(true, "Partner 注册成功"); return; }
                if (r.code() == 409) { cb.done(true, "Partner 已经注册"); return; }
                cb.done(false, "Partner 注册 HTTP " + r.code() + compact(text));
            }
        } catch (Exception e) { cb.done(false, "Partner 注册失败: " + e.getClass().getSimpleName()); }
    }

    private String partnerToken(String clientId, String secret, String audience) throws Exception {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", secret)
                .add("audience", audience)
                .add("scope", "openid vehicle_device_data")
                .build();
        Request req = new Request.Builder().url("https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token").post(body).build();
        try (Response r = http.newCall(req).execute()) {
            String text = r.body() == null ? "" : r.body().string();
            if (!r.isSuccessful()) return "";
            return new JSONObject(text).optString("access_token", "");
        }
    }

    private static String cleanDomain(String d) {
        if (d == null) return "";
        return d.trim().replaceFirst("^https?://", "").replaceAll("/+$", "").toLowerCase();
    }

    private static String compact(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace('\n',' ').replace('\r',' ');
        return s.length() > 220 ? " - " + s.substring(0,220) : " - " + s;
    }
}
