package com.gptlibrary.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class VectorNormalizationUtilsTest {

    @Test
    void l2NormalizeVectorProducesUnitLength() {
        float[] vector = new float[]{3.0f, 4.0f}; // magnitude 5.0
        float[] normalized = VectorNormalizationUtils.l2Normalize(vector);

        assertThat(normalized).hasSize(2);
        assertThat(normalized[0]).isEqualTo(0.6f, within(1e-5f));
        assertThat(normalized[1]).isEqualTo(0.8f, within(1e-5f));

        double sumSquares = (double) normalized[0] * normalized[0] + (double) normalized[1] * normalized[1];
        assertThat(Math.sqrt(sumSquares)).isEqualTo(1.0, within(1e-5));
    }

    @Test
    void l2NormalizeHighDimensionalVectorProducesUnitLength() {
        float[] vector = new float[768];
        for (int i = 0; i < 768; i++) {
            vector[i] = (float) (Math.sin(i + 1) * 2.5);
        }

        float[] normalized = VectorNormalizationUtils.l2Normalize(vector);
        assertThat(normalized).hasSize(768);

        double sumSquares = 0.0;
        for (float val : normalized) {
            sumSquares += (double) val * val;
        }
        assertThat(Math.sqrt(sumSquares)).isEqualTo(1.0, within(1e-5));
    }

    @Test
    void rejectNullOrEmptyVector() {
        assertThatThrownBy(() -> VectorNormalizationUtils.l2Normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vector must not be null or empty");

        assertThatThrownBy(() -> VectorNormalizationUtils.l2Normalize(new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vector must not be null or empty");
    }

    @Test
    void rejectNonFiniteValues() {
        float[] nanVector = new float[]{1.0f, Float.NaN, 3.0f};
        assertThatThrownBy(() -> VectorNormalizationUtils.l2Normalize(nanVector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite");

        float[] infVector = new float[]{1.0f, Float.POSITIVE_INFINITY, 3.0f};
        assertThatThrownBy(() -> VectorNormalizationUtils.l2Normalize(infVector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite");
    }

    @Test
    void rejectZeroMagnitudeVector() {
        float[] zeroVector = new float[]{0.0f, 0.0f, 0.0f};
        assertThatThrownBy(() -> VectorNormalizationUtils.l2Normalize(zeroVector))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero-magnitude");
    }
}
