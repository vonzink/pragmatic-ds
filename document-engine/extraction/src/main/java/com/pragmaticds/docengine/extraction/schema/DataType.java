package com.pragmaticds.docengine.extraction.schema;

/** Mirrors the {@code extracted_field_data_type_check} constraint (V7). */
public enum DataType {
    STRING,
    NUMBER,
    DATE,
    ENUM,
    MONEY;

    public static DataType fromWire(String wire) {
        return valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
