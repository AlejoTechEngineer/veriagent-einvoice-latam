package org.veriagent.provider.client;

import org.veriagent.contracts.ContractJson;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.core.retry.RetryPolicies.RecoverableException;
import org.veriagent.provider.client.ProviderProtocol.BatchManifest;
import org.veriagent.provider.client.ProviderProtocol.Decision;
import org.veriagent.provider.client.ProviderProtocol.DocumentEnvelope;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Cliente de los proveedores según {@link ProviderProtocol}. Los errores de transporte, los tiempos agotados y
 * las respuestas 429/502/503/504 son recuperables ({@link ProviderUnavailableException}); cualquier otra
 * respuesta es una decisión del proveedor.
 */
public class ProviderClient {
    private final ProvidersProperties props;
    private final Path sftpRoot;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public ProviderClient(ProvidersProperties props, Path sftpRoot) {
        this.props = props;
        this.sftpRoot = sftpRoot;
    }

    /** Proveedor caído o lento: se puede reintentar. */
    public static class ProviderUnavailableException extends RecoverableException {
        private static final long serialVersionUID = 1L;

        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private ProvidersProperties.Provider cfg(String provider) {
        ProvidersProperties.Provider p = props.getEndpoints().get(provider);
        if (p == null) throw new IllegalArgumentException("Proveedor sin configuración: " + provider);
        return p;
    }

    /** Envío síncrono de un comprobante. */
    public Decision submit(String provider, String documentId, String format, String content, Map<String, String> extraHeaders) {
        ProvidersProperties.Provider p = cfg(provider);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(p.getBaseUrl() + ProviderProtocol.DOCUMENTS_PATH))
            .timeout(p.getTimeout())
            .header("X-Correlation-Id", CorrelationContext.current());
        extraHeaders.forEach(b::header);
        if ("text".equals(p.getSyncFormat())) {
            b.header("Content-Type", "text/plain; charset=utf-8")
                .header(ProviderProtocol.DOCUMENT_ID_HEADER, documentId)
                .POST(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8));
        } else {
            var env = new DocumentEnvelope(documentId, format, content, sha256(content));
            b.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ContractJson.mapper().writeValueAsString(env), StandardCharsets.UTF_8));
        }
        HttpResponse<String> r = send(provider, b.build());
        if (r.statusCode() == 200) return ContractJson.mapper().readValue(r.body(), Decision.class);
        return new Decision(documentId, "REJECTED", "HTTP_" + r.statusCode(), truncate(r.body()), null, null);
    }

    /** Envío de un lote: ZIP por HTTP (Alfa) o archivo y manifiesto en la carpeta SFTP (Beta). */
    public void submitBatch(String provider, BatchManifest manifest, Map<String, String> entries) {
        ProvidersProperties.Provider p = cfg(provider);
        if ("sftp-folder".equals(p.getBatchMode())) {
            writeToFolder(provider, manifest, entries);
            return;
        }
        byte[] zip = zip(manifest, entries);
        String boundary = "veriagent-" + UUID.randomUUID();
        byte[] body = multipart(boundary, manifest.batchId() + ".zip", zip);
        HttpRequest req = HttpRequest.newBuilder(URI.create(p.getBaseUrl() + ProviderProtocol.BATCHES_PATH))
            .timeout(p.getTimeout().multipliedBy(4))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("X-Correlation-Id", CorrelationContext.current())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<String> r = send(provider, req);
        if (r.statusCode() != 202 && r.statusCode() != 200) {
            throw new IllegalStateException("El proveedor " + provider + " rechazó el lote " + manifest.batchId()
                + ": HTTP " + r.statusCode() + " " + truncate(r.body()));
        }
    }

    private HttpResponse<String> send(String provider, HttpRequest req) {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int s = r.statusCode();
            if (s == 429 || s == 502 || s == 503 || s == 504) {
                throw new ProviderUnavailableException("Proveedor " + provider + " no disponible: HTTP " + s, null);
            }
            return r;
        } catch (HttpTimeoutException e) {
            throw new ProviderUnavailableException("Tiempo agotado con el proveedor " + provider, e);
        } catch (ConnectException e) {
            throw new ProviderUnavailableException("No se pudo conectar con el proveedor " + provider, e);
        } catch (IOException e) {
            throw new ProviderUnavailableException("Error de comunicación con el proveedor " + provider + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Envío interrumpido", e);
        }
    }

    private void writeToFolder(String provider, BatchManifest manifest, Map<String, String> entries) {
        Path inbox = sftpRoot.resolve("outbound").resolve(provider).resolve("inbox");
        try {
            Files.createDirectories(inbox);
            String name = SafeFiles.sanitize(manifest.batchId());
            StringBuilder seg = new StringBuilder();
            for (var d : manifest.documents()) seg.append(entries.get(d.entryName()));
            // Se escribe primero el manifiesto y al final el archivo de datos, con movimiento atómico, para que el
            // proveedor nunca lea un lote a medias.
            atomicWrite(SafeFiles.resolveInside(inbox, name + ".manifest.json"),
                ContractJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            atomicWrite(SafeFiles.resolveInside(inbox, name + ".seg"), seg.toString());
        } catch (IOException e) {
            throw new ProviderUnavailableException("No se pudo depositar el lote en la carpeta SFTP de " + provider, e);
        }
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static byte[] zip(BatchManifest manifest, Map<String, String> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(ContractJson.mapper().writeValueAsBytes(manifest));
            zos.closeEntry();
            for (var d : manifest.documents()) {
                zos.putNextEntry(new ZipEntry(d.entryName()));
                zos.write(entries.get(d.entryName()).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    private static byte[] multipart(String boundary, String filename, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
            + "\"\r\nContent-Type: application/zip\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
