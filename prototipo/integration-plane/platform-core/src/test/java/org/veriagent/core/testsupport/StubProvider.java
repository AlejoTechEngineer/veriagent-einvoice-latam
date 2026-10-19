package org.veriagent.core.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proveedor mínimo para pruebas de componente (los simuladores completos están en provider-simulators).
 * Modos: {@code OK} autoriza, {@code REJECT} rechaza y {@code DOWN} responde 503.
 */
public final class StubProvider implements AutoCloseable {
    public enum Mode { OK, REJECT, DOWN }

    private static final Pattern DOC_ID = Pattern.compile("\"documentId\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpServer server;
    private volatile Mode mode = Mode.OK;
    private final AtomicInteger documents = new AtomicInteger();
    private final AtomicInteger batches = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public StubProvider() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/v1/documents", this::document);
        server.createContext("/api/v1/batches", this::batch);
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void mode(Mode m) { this.mode = m; }
    public int documents() { return documents.get(); }
    public int batches() { return batches.get(); }
    public int calls() { return calls.get(); }

    private void document(HttpExchange ex) throws IOException {
        calls.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (mode == Mode.DOWN) { reply(ex, 503, "{\"error\":\"no disponible\"}"); return; }
        documents.incrementAndGet();
        String id = ex.getRequestHeaders().getFirst("X-Document-Id");
        if (id == null) {
            Matcher m = DOC_ID.matcher(body);
            id = m.find() ? m.group(1) : "desconocido";
        }
        boolean ok = mode == Mode.OK;
        reply(ex, 200, "{\"documentId\":\"" + id + "\",\"status\":\"" + (ok ? "AUTHORIZED" : "REJECTED") + "\",\"code\":\""
            + (ok ? "00" : "R-CAT") + "\",\"message\":\"" + (ok ? "Autorizado" : "Código de catálogo rechazado por el proveedor")
            + "\",\"authorizationNumber\":" + (ok ? "\"AUT-" + documents.get() + "\"" : "null") + ",\"receiptSha256\":null}");
    }

    private void batch(HttpExchange ex) throws IOException {
        calls.incrementAndGet();
        ex.getRequestBody().readAllBytes();
        if (mode == Mode.DOWN) { reply(ex, 503, "{\"error\":\"no disponible\"}"); return; }
        batches.incrementAndGet();
        reply(ex, 202, "{\"accepted\":true}");
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    @Override
    public void close() { server.stop(0); }
}
