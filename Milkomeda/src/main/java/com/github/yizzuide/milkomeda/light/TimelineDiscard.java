package com.github.yizzuide.milkomeda.light;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Date;

/**
 * TimelineDiscard
 *
 * 时间线丢弃方案
 *
 * @since 1.8.0
 * @version 2.0.3
 * @author yizzuide
 * Create at 2019/06/28 16:25
 */
public class TimelineDiscard extends SortDiscard {

    @Override
    public Class<? extends SortSpot> spotClazz() {
        return TimelineSpot.class;
    }

    @Override
    public Spot<Serializable, Object> deform(String key, Spot<Serializable, Object> spot, long expire) {
        TimelineSpot<Serializable, Object> timelineSpot = (TimelineSpot<Serializable, Object>) super.deform(key, spot, expire);
        if (null == timelineSpot.getTime()) {
            timelineSpot.setTime(new Date());
        }
        return timelineSpot;
    }

    @Override
    public boolean ascend(Spot<Serializable, Object> spot) {
        TimelineSpot<Serializable, Object> timelineSpot = (TimelineSpot<Serializable, Object>) spot;
        timelineSpot.setTime(new Date());
        return false;
    }

    @Override
    protected Comparator<? extends SortSpot<Serializable, Object>> comparator() {
        return Comparator.<TimelineSpot<Serializable, Object>, Date>comparing(TimelineSpot::getTime);
    }
}
