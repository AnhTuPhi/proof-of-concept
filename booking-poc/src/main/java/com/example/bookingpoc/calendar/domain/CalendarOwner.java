package com.example.bookingpoc.calendar.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "calendar_owners")
public class CalendarOwner {

    @Id
    private String id;

    @Column(nullable = false)
    private String displayName;

    /** IANA tz id, e.g. "Asia/Ho_Chi_Minh". Stored separately from any UTC instant. */
    @Column(nullable = false, length = 64)
    private String timezone;

    /** Minimum gap between back-to-back bookings, applied as an effective buffer on both sides. */
    @Column(nullable = false)
    private int bufferMinutes;

    protected CalendarOwner() {}

    public CalendarOwner(String id, String displayName, String timezone, int bufferMinutes) {
        this.id = id;
        this.displayName = displayName;
        this.timezone = timezone;
        this.bufferMinutes = bufferMinutes;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getTimezone() { return timezone; }
    public int getBufferMinutes() { return bufferMinutes; }
}
