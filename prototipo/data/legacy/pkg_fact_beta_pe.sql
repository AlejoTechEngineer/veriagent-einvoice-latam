-- Sistema heredado SINTÉTICO de facturación para Perú (proveedor Beta). Ficticio: no proviene de ningún sistema
-- real. Sirve de entrada al agente de extracción de legado, que debe recuperar las reglas con archivo y línea.
CREATE OR REPLACE PACKAGE BODY pkg_fact_beta_pe AS

  -- Tipo de documento comercial a código del catálogo 01.
  FUNCTION tipo_doc(p_tipo IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    IF p_tipo = 'INVOICE' THEN RETURN '01';
    ELSIF p_tipo = 'CREDIT_NOTE' THEN RETURN '07';
    ELSIF p_tipo = 'DEBIT_NOTE' THEN RETURN '08';
    END IF;
    RAISE_APPLICATION_ERROR(-20001, 'Tipo de documento no soportado');
  END tipo_doc;

  -- Impuesto interno a código del catálogo 05.
  FUNCTION tributo(p_codigo IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    IF p_codigo = 'IGV18' THEN RETURN '1000';
    ELSIF p_codigo = 'EXONERADO' THEN RETURN '9997';
    END IF;
    RAISE_APPLICATION_ERROR(-20002, 'Impuesto no soportado');
  END tributo;

  -- El separador "|" está reservado en el formato por segmentos.
  FUNCTION limpiar_texto(p_texto IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN REPLACE(p_texto, '|', '/');
  END limpiar_texto;

  PROCEDURE armar_segmentos(r IN t_comprobante) IS
    v_cliente VARCHAR2(200);
    v_desc    VARCHAR2(500);
  BEGIN
    v_cliente := limpiar_texto(r.razon_social_cliente);
    FOR l IN r.lineas.FIRST .. r.lineas.LAST LOOP
      v_desc := limpiar_texto(r.lineas(l).descripcion);
      escribir_det(l, v_desc, tributo(r.lineas(l).impuesto));
    END LOOP;
    escribir_enc(tipo_doc(r.tipo), v_cliente);
  END armar_segmentos;

END pkg_fact_beta_pe;
