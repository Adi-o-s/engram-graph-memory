package com.memora.extraction;

import java.util.List;

/**
 * Turns free text into candidate memories. The default implementation is deterministic and
 * rule-based; an alternative (for example a local model) can replace it by providing another bean
 * of this type marked {@code @Primary}.
 */
public interface MemoryExtractor {

    String name();

    List<ExtractedMemory> extract(ExtractionInput input);
}
