package org.tfg.console;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cliente de la API de revisión (la misma que usa la consola web). Envía el token de acceso en cada petición;
 * la API toma de él la identidad de quien decide, así que el cliente nunca envía el nombre del revisor.
 */
final class ReviewApi {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private volatile String token = "";

    ReviewApi(String base) { this.base = base; }

    void token(String token) { this.token = token == null ? "" : token.trim(); }

    /** Identidad y rol del token. */
    Map<String, Object> me() throws Exception {
        return MiniJson.obj(MiniJson.parse(get("/api/me")));
    }

    List<Object> proposals() throws Exception {
        return MiniJson.list(MiniJson.parse(get("/api/proposals")));
    }

    Map<String, Object> proposal(String id) throws Exception {
        return MiniJson.obj(MiniJson.parse(get("/api/proposals/" + id)));
    }

    /** Devuelve null si la decisión se registró; si no, el mensaje de error de la API. */
    String decide(String id, String decision, String comment) throws Exception {
        String body = "{\"decision\":" + q(decision) + ",\"comment\":" + q(comment) + "}";
        HttpResponse<String> r = http.send(request("/api/proposals/" + id + "/decision")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) return null;
        if (r.statusCode() == 401) return "Falta un token válido.";
        Object detail = MiniJson.obj(MiniJson.parse(r.body())).get("detail");
        return detail instanceof String d ? d : "Revisa el comentario.";
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private String get(String path) throws Exception {
        HttpResponse<String> r = http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401) throw new IllegalStateException("falta un token válido");
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " en " + path);
        return r.body();
    }

    private static String q(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
