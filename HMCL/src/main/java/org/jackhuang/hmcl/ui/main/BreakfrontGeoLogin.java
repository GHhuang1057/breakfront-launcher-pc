/*
 * BREAKFRONT Launcher — Geekhonize 网页登录（设备码授权）
 *
 * 登录全过程在浏览器完成（auth.geekhonize.top 的「设备授权」页），启动器只负责：
 *   1. POST /api/v1/auth/device/start 取 6 位授权码；
 *   2. 打开登录页并把授权码告诉玩家；
 *   3. 轮询 /api/v1/auth/device/poll，授权通过后拿到 access_token；
 *   4. 把令牌写入游戏目录的 config/breakfront-client.properties，游戏内 mod
 *      （com.breakfront.client.geo.GeoSession）启动时即自动登录，无需手抄令牌。
 */
package org.jackhuang.hmcl.ui.main;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class BreakfrontGeoLogin {

    private static final String BASE = "https://auth.geekhonize.top";
    private static final String DEVICE_START = BASE + "/api/v1/auth/device/start";
    private static final String DEVICE_POLL = BASE + "/api/v1/auth/device/poll";

    private static final String KEY_TOKEN = "auth.token";
    private static final String KEY_USER = "auth.username";
    private static final String KEY_EXPIRES = "auth.expires";
    private static final String PROPS_NAME = "breakfront-client.properties";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private BreakfrontGeoLogin() {
    }

    /** 在后台线程跑完整授权流程；UI 交互经 Platform.runLater 回到 FX 线程。 */
    public static void start() {
        Thread t = new Thread(BreakfrontGeoLogin::run, "Breakfront-GeoLogin");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            @Nullable String code = requestCode();
            if (code == null) {
                dialog("无法连接 Geekhonize 登录服务，请检查网络后重试。");
                return;
            }

            Platform.runLater(() -> FXUtils.openLink(BASE + "/"));
            dialog("已打开 Geekhonize 登录页。\n\n"
                    + "请在浏览器里登录，然后进入「设备授权」，输入下面的授权码：\n\n"
                    + "        " + code + "\n\n"
                    + "授权完成后本提示会自动闭环，无需其它操作。");

            @Nullable String token = poll(code);
            if (token == null) {
                dialog("Geekhonize 登录未完成（未授权或已超时），请重试。");
                return;
            }

            Path file = writeSession(token);
            dialog("Geekhonize 登录成功！\n\n令牌已写入：\n" + file
                    + "\n\n直接进入游戏即可自动登录，无需再手抄令牌。");
        } catch (Throwable e) {
            dialog("Geekhonize 登录失败：" + e);
        }
    }

    private static @Nullable String requestCode() throws Exception {
        HttpResponse<String> resp = post(DEVICE_START, "{}");
        if (resp == null || resp.statusCode() / 100 != 2) {
            return null;
        }
        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean() || !o.has("data")) {
            return null;
        }
        JsonObject data = o.getAsJsonObject("data");
        return data.has("code") ? data.get("code").getAsString() : null;
    }

    /** 轮询直到授权通过（最长 10 分钟）。返回 access_token，超时返回 null。 */
    private static @Nullable String poll(String code) throws Exception {
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
        String body = "{\"code\":\"" + code + "\"}";
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000);
            HttpResponse<String> resp = post(DEVICE_POLL, body);
            if (resp == null || resp.statusCode() / 100 != 2) {
                continue;
            }
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
                continue;
            }
            if (o.has("access_token")) {
                String token = o.get("access_token").getAsString();
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }
        return null;
    }

    private static @Nullable HttpResponse<String> post(String url, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/json; charset=utf-8")
                    .header("user-agent", "breakfront-launcher")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return null;
        }
    }

    /** 把令牌写进游戏目录的 config/breakfront-client.properties（保留其它配置键）。 */
    static Path writeSession(String token) throws Exception {
        Path file = gameDirectory().resolve("config").resolve(PROPS_NAME);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            lines.addAll(Files.readAllLines(file));
        }
        lines.removeIf(l -> l.startsWith(KEY_TOKEN + "=")
                || l.startsWith(KEY_USER + "=")
                || l.startsWith(KEY_EXPIRES + "="));
        lines.add(KEY_TOKEN + "=" + token);
        lines.add(KEY_USER + "=" + claim(token, "sub"));
        lines.add(KEY_EXPIRES + "=" + expiry(token));
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private static Path gameDirectory() {
        try {
            @Nullable HMCLGameInstance instance = GameDirectoryManager.getSelectedInstance();
            if (instance != null) {
                Path root = instance.getInstanceRoot();
                if (root != null) {
                    return root;
                }
            }
        } catch (Throwable ignored) {
        }
        return Metadata.MINECRAFT_DIRECTORY;
    }

    /** 读取 JWT payload 里的字符串 claim（失败返回空串）。 */
    private static String claim(String jwt, String key) {
        try {
            JsonObject payload = payload(jwt);
            return payload != null && payload.has(key) ? payload.get(key).getAsString() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    /** 读取 JWT payload 里的 exp（epoch 秒，失败返回 0）。 */
    private static long expiry(String jwt) {
        try {
            JsonObject payload = payload(jwt);
            return payload != null && payload.has("exp") ? payload.get("exp").getAsLong() : 0L;
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static @Nullable JsonObject payload(String jwt) {
        if (jwt == null) {
            return null;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        String b64 = parts[1].replace('-', '+').replace('_', '/');
        while (b64.length() % 4 != 0) {
            b64 += "=";
        }
        String json = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void dialog(String text) {
        Platform.runLater(() -> Controllers.dialog(text));
    }
}
