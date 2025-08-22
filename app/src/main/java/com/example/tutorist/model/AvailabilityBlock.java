package com.example.tutorist.model;


public class AvailabilityBlock {
    public String id;       // Firestore docId
    public int dayOfWeek;   // 1..7 (Mon..Sun)
    public int startHour;   // 0..23
    public int endHour;     // 1..24 (exclusive)

    public AvailabilityBlock() {}

    public AvailabilityBlock(String id, int dayOfWeek, int startHour, int endHour) {
        this.id = id;
        this.dayOfWeek = dayOfWeek;
        this.startHour = startHour;
        this.endHour = endHour;
    }

    @Override public String toString() {
        return String.format("%02d:00–%02d:00", startHour, endHour);
    }
}
