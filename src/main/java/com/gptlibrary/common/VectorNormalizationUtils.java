package com.gptlibrary.common;

public final class VectorNormalizationUtils {

    private VectorNormalizationUtils() {}

    /**
     * Performs L2-normalization on a float array vector:
     * v_normalized = v / sqrt(sum(v_i^2))
     *
     * Required by Google when embedding dimensionality is reduced from 3072 to 768.
     * Safely validates that the input vector is non-null, non-empty, finite, and non-zero.
     *
     * @param vector the input float array
     * @return a new unit-length float array with L2 norm equal to 1.0
     * @throws IllegalArgumentException if vector is null, empty, contains non-finite values (NaN/Infinity), or has zero magnitude
     */
    public static float[] l2Normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Vector must not be null or empty.");
        }

        double sumSquares = 0.0;
        for (float val : vector) {
            if (Float.isNaN(val) || Float.isInfinite(val)) {
                throw new IllegalArgumentException("Vector contains invalid non-finite value (NaN or Infinity).");
            }
            sumSquares += (double) val * val;
        }

        double norm = Math.sqrt(sumSquares);
        if (norm < 1e-9) {
            throw new IllegalArgumentException("Cannot normalize a zero-magnitude vector.");
        }

        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }
}
