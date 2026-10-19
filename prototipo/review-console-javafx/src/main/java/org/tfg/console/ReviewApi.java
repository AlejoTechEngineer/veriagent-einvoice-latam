package org.tfg.console;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Cliente de la API de revisión (la misma que usa la consola web). */
final class ReviewApi {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    ReviewApi(String base) { this.base = base; }

    List<Object> proposals() throws Exception {
        return MiniJson.list(MiniJson.parse(get("/api/proposals")));
    }

    Map<String, Object> proposal(String id) throws Exception {
        return MiniJson.obj(MiniJson.parse(get("/api/proposals/" + id)));
    }

    /** Devuelve null si la decisión se registró; si no, el mensaje de error de la API. */
    String decide(String id, String decision, String reviewer, String comment) throws Exception {
        String body = "{\"decision\":" + q(decision) + ",\"reviewer\":" + q(reviewer) + ",\"comment\":" + q(comment) + "}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/proposals/" + id + "/decision"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) return null;
        Object detail = MiniJson.obj(MiniJson.parse(r.body())).get("detail");
        return detail instanceof String d ? d : "Revisa el revisor (mínimo 3 caracteres) y el comentario.";
    }

    private String get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " en " + path);
        return r.body();
    }

    private static String q(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
