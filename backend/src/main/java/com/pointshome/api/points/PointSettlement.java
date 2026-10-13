package com.pointshome.api.points;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PointSettlement {
    private final PointService points;

    public PointSettlement(PointService points) {
        this.points = points;
    }

    @Scheduled(fixedDelay = 30_000)
    public void settleDueDeductions() {
        for (String id : points.dueActionIds()) points.settle(id);
    }
}
