package org.tfg.kit.core;

/** Taxonomía de defectos de mapeo (capítulo 4 de la memoria). */
public enum DefectType {
    D1("Código de catálogo incorrecto"),
    D2("Campos intercambiados o importes incoherentes"),
    D3("Literal mal formateado"),
    D4("Campo obligatorio ausente o vacío"),
    D5("Campo obsoleto por versión del estándar"),
    D6("Carácter reservado en el contenido"),
    E0("Error estructural no clasificado");

    private final String descripcion;
    DefectType(String descripcion) { this.descripcion = descripcion; }
    public String descripcion() { return descripcion; }
}
