package org.tfg.kit.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.tfg.kit.VerificationKit;
import org.tfg.kit.core.Mode;
import org.tfg.kit.core.VerificationReport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Servicio HTTP del kit (solo JDK). Lo consume el servidor MCP validador y el pipeline. */
public final class KitHttpServer {
    private static final int MAX_BODY = 2 * 1024 * 1024;

    /** Escucha solo en la interfaz local; para contenedores se usa {@link #start(VerificationKit, String, int)}. */
    public static HttpServer start(VerificationKit kit, int port) throws IOException {
        return start(kit, "127.0.0.1", port);
    }

    public static HttpServer start(VerificationKit kit, String bindAddress, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\",\"spec\":\"" + kit.spec().sha256() + "\"}"));
        server.createContext("/verify", ex -> {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"usar POST\"}"); return; }
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { send(ex, 413, "{\"error\":\"documento demasiado grande\"}"); return; }
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            Mode mode = Mode.valueOf(q.getOrDefault("mode", "RUNTIME"));
            String content = new String(body, StandardCharsets.UTF_8);
            VerificationReport r = "seg".equals(q.get("format"))
                ? kit.verifySegments(q.getOrDefault("id", "doc"), content, mode, null)
                : kit.verifyXml(q.getOrDefault("id", "doc"), content, mode, null);
            send(ex, r.passed() ? 200 : 422, r.toJson());
        });
        server.start();
        return server;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2) m.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
