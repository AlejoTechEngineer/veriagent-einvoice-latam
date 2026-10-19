package org.tfg.kit.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Documento normativo de origen. Su hash liga cada regla con la versión exacta del texto. */
public record SpecDocument(String name, String sha256) {
    public static SpecDocument load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new SpecDocument(path.getFileName().toString(), hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
