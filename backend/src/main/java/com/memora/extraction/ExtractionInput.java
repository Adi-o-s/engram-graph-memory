package com.memora.extraction;

/** Text to extract from plus who said it; personal statements ("I prefer...") need a human author. */
public record ExtractionInput(String text, boolean authoredByUser) {
}
