package org.tfg.kit.core;

/** Modos de verificación comparados en la evaluación. */
public enum Mode {
    /** Solo validación estructural (XSD o conteo de campos). Línea base. */
    SCHEMA_ONLY,
    /** Kit en ejecución: estructura + reglas de negocio con procedencia. */
    RUNTIME,
    /** Gate de CI: RUNTIME + comparación con fixtures validados por personas. */
    CI
}
