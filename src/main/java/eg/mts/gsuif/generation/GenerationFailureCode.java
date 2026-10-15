package eg.mts.gsuif.generation;

/** Persistable failure codes are fixed values, never provider or exception text. */
public enum GenerationFailureCode {
    CONFIG_MISSING,
    VALIDATION_FAILED,
    MODEL_UNAVAILABLE,
    INVALID_SOURCE_MAP,
    UNEXPECTED_FAILURE,
    RESULT_VALIDATION_FAILED
}
