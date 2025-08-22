package com.example.tutorist.util;

import com.example.tutorist.model.AvailabilityBlock;
import java.util.List;

public class ValidationUtil {
    public static boolean isValidPrice(double p) { return p > 0; }

    public static boolean isValidBlock(int start, int end) {
        return start >= 0 && end <= 24 && end > start;
    }

    public static boolean overlaps(AvailabilityBlock a, AvailabilityBlock b) {
        // [a.start, a.end) with [b.start, b.end)
        return a.startHour < b.endHour && b.startHour < a.endHour;
    }

    public static boolean hasOverlap(List<AvailabilityBlock> list, int start, int end) {
        AvailabilityBlock x = new AvailabilityBlock(null, 0, start, end);
        for (AvailabilityBlock b : list) if (overlaps(x, b)) return true;
        return false;
    }
}
