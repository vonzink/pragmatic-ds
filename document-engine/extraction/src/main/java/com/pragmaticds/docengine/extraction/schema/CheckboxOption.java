package com.pragmaticds.docengine.extraction.schema;

/**
 * One CHECKBOX_STATE option: a label anchor mapped to the enum code the field takes when the
 * checkbox nearest that label is the one checked box on the page.
 */
public record CheckboxOption(LabelSpec label, String value) {}
