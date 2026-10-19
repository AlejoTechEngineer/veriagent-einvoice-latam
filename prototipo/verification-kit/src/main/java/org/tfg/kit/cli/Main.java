package org.tfg.kit.cli;

import org.tfg.kit.VerificationKit;
import org.tfg.kit.core.Mode;
import org.tfg.kit.core.VerificationReport;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI del kit.
 *   verify --home DIR --format xml|seg --mode SCHEMA_ONLY|RUNTIME|CI FILE [EXPECTED]
 *   batch  --home DIR MANIFEST.csv OUT.jsonl     (manifest: id,format,mode,file,expected)
 *   serve  --home DIR --port 8085
 * Código de salida 0 si todo pasa y 2 si hay hallazgos: así funciona como gate de CI.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(64); }
        String cmd = args[0];
        Path home = Path.of(arg(args, "--home", "."));
        VerificationKit kit = new VerificationKit(home);
        switch (cmd) {
            case "verify" -> {
                String format = arg(args, "--format", "xml");
                Mode mode = Mode.valueOf(arg(args, "--mode", "RUNTIME"));
                List<String> pos = positional(args);
                String content = Files.readString(Path.of(pos.get(0)));
                String expected = pos.size() > 1 ? Files.readString(Path.of(pos.get(1))) : null;
                VerificationReport r = "seg".equals(format)
                    ? kit.verifySegments(pos.get(0), content, mode, expected)
                    : kit.verifyXml(pos.get(0), content, mode, expected);
                System.out.println(r.toJson());
                System.exit(r.passed() ? 0 : 2);
            }
            case "batch" -> {
                List<String> pos = positional(args);
                int failed = 0, total = 0;
                try (BufferedWriter w = Files.newBufferedWriter(Path.of(pos.get(1)), StandardCharsets.UTF_8)) {
                    for (String line : Files.readAllLines(Path.of(pos.get(0)))) {
                        if (line.isBlank() || line.startsWith("id,")) continue;
                        String[] p = line.split(",", -1);
                        Mode mode = Mode.valueOf(p[2]);
                        String content = Files.readString(Path.of(p[3]));
                        String expected = p.length > 4 && !p[4].isBlank() ? Files.readString(Path.of(p[4])) : null;
                        VerificationReport r = "seg".equals(p[1])
                            ? kit.verifySegments(p[0], content, mode, expected)
                            : kit.verifyXml(p[0], content, mode, expected);
                        w.write(r.toJson()); w.newLine();
                        total++; if (!r.passed()) failed++;
                    }
                }
                System.err.println("Verificados " + total + " documentos; con hallazgos: " + failed);
            }
            case "serve" -> {
                int port = Integer.parseInt(arg(args, "--port", "8085"));
                KitHttpServer.start(kit, port);
                System.err.println("Kit de verificación escuchando en el puerto " + port);
            }
            default -> { usage(); System.exit(64); }
        }
    }

    private static String arg(String[] a, String name, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(name)) return a[i + 1];
        return def;
    }

    private static List<String> positional(String[] a) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 1; i < a.length; i++) {
            if (a[i].startsWith("--")) { i++; continue; }
            out.add(a[i]);
        }
        return out;
    }

    private static void usage() {
        System.err.println("Uso: verify|batch|serve --home DIR ... (ver Javadoc de Main)");
    }
}
