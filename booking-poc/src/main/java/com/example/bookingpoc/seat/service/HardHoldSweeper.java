package com.example.bookingpoc.seat.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The {@code @SchedulerLock} annotation is a no-op unless
 * {@code booking.scheduler.distributed=true} activates
 * {@link com.example.bookingpoc.config.SchedulerLockConfig}.
 *
 * <p>{@code lockAtMostFor} is the safety net: if the holding pod crashes,
 * no other pod can take over for 2 minutes. {@code lockAtLeastFor}
 * prevents the next interval from immediately re-running on a different
 * pod after a fast sweep.
 */
@Component
public class HardHoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HardHoldSweeper.class);
    private final SeatService seatService;

    public HardHoldSweeper(SeatService seatService) {
        this.seatService = seatService;
    }

    @Scheduled(fixedDelayString = "${booking.seat.sweep-interval-seconds:30}000")
    @SchedulerLock(name = "hardHoldSweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT25S")
    public void sweep() {
        int released = seatService.sweepExpired();
        if (released > 0) {
            log.info("Released {} expired hard holds", released);
        }
    }
}
