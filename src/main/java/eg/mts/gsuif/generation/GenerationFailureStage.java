package eg.mts.gsuif.generation;

/** Safe, bounded provenance for failures before a complete generation result exists. */
public enum GenerationFailureStage {
    CONFIG, CONTRACT, AI, ASSEMBLY, FINAL_VALIDATION
}
