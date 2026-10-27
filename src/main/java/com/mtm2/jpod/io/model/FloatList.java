package com.mtm2.jpod.io.model;

import java.util.Arrays;

/** A growable float array, so building meshes does not box every coordinate. */
final class FloatList {
    private float[] values;
    private int size;

    FloatList() {
        this(64);
    }

    FloatList(int capacity) {
        values = new float[Math.max(4, capacity)];
    }

    void add(float value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        values[size++] = value;
    }

    int size() {
        return size;
    }

    float[] toArray() {
        return Arrays.copyOf(values, size);
    }
}
