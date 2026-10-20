package org.veriagent.datagen;

import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.InvoiceRequest;
import tools.jackson.databind.node.ArrayNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Línea de órdenes del generador.
 * <pre>
 *   generate --home DIR --country CO --count 1000 --seed 2026 [--offset 0] --out lote.json
 *   generate --home DIR --mix CO:600,PE:400 --seed 2026 --out lote.jsonl     (varios países; .jsonl: un comprobante por línea)
 *   fixtures --home DIR --country CO --out fixtures.json
 *   defects  --home DIR --seed 2026 [--offset 500000] --out defectos.json   (D1, D4, D2 y D3 de datos, uno de cada)
 * </pre>
 */
public final class GeneratorCli {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Uso: generate|fixtures|defects --home DIR ... (ver Javadoc)");
            System.exit(64);
        }
        Path home = Path.of(arg(args, "--home", "."));
        Numbering numbering = Numbering.load(home.resolve("data/numeracion"));
        Path out = Path.of(arg(args, "--out", "salida.json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ArrayNode arr = ContractJson.mapper().createArrayNode();
        switch (args[0]) {
            case "generate" -> {
                // --mix CO:600,PE:400 mezcla países en un solo lote; si no, --country y --count.
                String mix = arg(args, "--mix", arg(args, "--country", "CO") + ":" + arg(args, "--count", "100"));
                var g = new InvoiceGenerator(numbering, Long.parseLong(arg(args, "--seed", "2026")), Long.parseLong(arg(args, "--offset", "0")));
                for (String part : mix.split(",")) {
                    String[] cn = part.trim().split(":");
                    List<InvoiceRequest> invs = g.generate(Country.valueOf(cn[0]), Integer.parseInt(cn[1]));
                    invs.forEach(i -> arr.add(ContractJson.mapper().valueToTree(i)));
                }
            }
            case "fixtures" -> InvoiceGenerator.fixtures(numbering, Country.valueOf(arg(args, "--country", "CO")))
                .forEach(i -> arr.add(ContractJson.mapper().valueToTree(i)));
            case "defects" -> {
                var g = new InvoiceGenerator(numbering, Long.parseLong(arg(args, "--seed", "2026")), Long.parseLong(arg(args, "--offset", "500000")));
                for (InvoiceGenerator.DataDefect d : InvoiceGenerator.DataDefect.values()) arr.add(g.defective(d));
            }
            default -> {
                System.err.println("Orden desconocida: " + args[0]);
                System.exit(64);
            }
        }
        if (out.getFileName().toString().endsWith(".jsonl")) {
            StringBuilder sb = new StringBuilder();
            arr.forEach(n -> sb.append(ContractJson.mapper().writeValueAsString(n)).append('\n'));
            Files.writeString(out, sb.toString());
        } else {
            Files.writeString(out, ContractJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(arr));
        }
        System.err.println("Escritos " + arr.size() + " comprobantes en " + out);
    }

    private static String arg(String[] a, String name, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(name)) return a[i + 1];
        return def;
    }
}
