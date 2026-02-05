package me.jling.imagedemo.image.core.tool;

import java.time.Instant;

public record ImageInfo(
        int width,
        int height,
        Double lat,
        Double lng,
        String placeText,
        Instant takenAt,
        String make,
        String model
) {
}
