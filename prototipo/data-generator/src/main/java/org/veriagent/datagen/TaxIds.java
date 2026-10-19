package org.veriagent.datagen;

import java.util.random.RandomGenerator;

/**
 * Identificadores tributarios falsos pero con dígito de control válido. Los algoritmos son los públicos:
 * dígito de verificación del NIT (DIAN, Colombia), dígito verificador del RUC (SUNAT, Perú) y Luhn para el país
 * sintético XX. Ningún número corresponde a una persona o empresa real: se generan al azar y se marcan como sintéticos.
 */
public final class TaxIds {
    private static final int[] NIT_WEIGHTS = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};
    private static final int[] RUC_WEIGHTS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    private static final String ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";

    private TaxIds() {}

    /** Dígito de verificación del NIT: pesos primos de derecha a izquierda, módulo 11. */
    public static int nitCheckDigit(String base) {
        int sum = 0;
        for (int i = 0; i < base.length(); i++) {
            int digit = base.charAt(base.length() - 1 - i) - '0';
            sum += digit * NIT_WEIGHTS[i];
        }
        int r = sum % 11;
        return r > 1 ? 11 - r : r;
    }

    /** NIT de empresa de 9 dígitos con su dígito de verificación: {@code 900123456-8}. */
    public static String nit(RandomGenerator rng) {
        String base = String.valueOf(800_000_000 + rng.nextInt(199_999_999));
        return base + "-" + nitCheckDigit(base);
    }

    public static boolean validNit(String nit) {
        if (!nit.matches("\\d{9}-\\d")) return false;
        return nitCheckDigit(nit.substring(0, 9)) == nit.charAt(10) - '0';
    }

    /** Dígito verificador del RUC: pesos 5,4,3,2,7,6,5,4,3,2 sobre los 10 primeros dígitos, módulo 11. */
    public static int rucCheckDigit(String first10) {
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += (first10.charAt(i) - '0') * RUC_WEIGHTS[i];
        int r = 11 - (sum % 11);
        return r == 10 ? 0 : r == 11 ? 1 : r;
    }

    /** RUC de persona jurídica (prefijo 20) de 11 dígitos con su dígito verificador. */
    public static String ruc(RandomGenerator rng) {
        String first10 = "20" + String.format("%08d", rng.nextInt(100_000_000));
        return first10 + rucCheckDigit(first10);
    }

    public static boolean validRuc(String ruc) {
        return ruc.matches("\\d{11}") && rucCheckDigit(ruc.substring(0, 10)) == ruc.charAt(10) - '0';
    }

    /** Dígito de control de Luhn. */
    public static int luhnCheckDigit(String base) {
        int sum = 0;
        boolean dbl = true;
        for (int i = base.length() - 1; i >= 0; i--) {
            int d = base.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    /** TIN de 10 dígitos del país sintético XX (9 + dígito de Luhn). */
    public static String tin(RandomGenerator rng) {
        String base = String.valueOf(100_000_000 + rng.nextInt(899_999_999));
        return base + luhnCheckDigit(base);
    }

    public static boolean validTin(String tin) {
        return tin.matches("\\d{10}") && luhnCheckDigit(tin.substring(0, 9)) == tin.charAt(9) - '0';
    }

    /** Cédula de ciudadanía (CO): 6 a 10 dígitos. */
    public static String cc(RandomGenerator rng) {
        return String.valueOf(10_000_000L + (long) (rng.nextDouble() * 1_189_999_999L));
    }

    /** DNI (PE): 8 dígitos. */
    public static String dni(RandomGenerator rng) {
        return String.format("%08d", 10_000_000 + rng.nextInt(89_999_999));
    }

    /** Documento alfanumérico en mayúsculas (carné de extranjería o pasaporte). */
    public static String alnum(RandomGenerator rng, int minLen, int maxLen) {
        int len = minLen + rng.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(ALNUM.charAt(rng.nextInt(24)));
        sb.append(ALNUM.charAt(rng.nextInt(24)));
        while (sb.length() < len) sb.append(ALNUM.charAt(24 + rng.nextInt(10)));
        return sb.toString();
    }
}
