package com.campusdash.domain.grab.model;

import com.campusdash.domain.errand.model.Errand;

/** 只表示数据库已提交事实，不包含 Redis 临时占位。 */
public record GrabProjection(long campusId, long errandId, long dbVersion, int round,
        String status, int slotTaken, Long ownerRunnerId) {
    public static GrabProjection from(Errand errand) {
        return new GrabProjection(errand.campusId(), errand.id(), errand.version(), errand.round(),
                errand.status().name(), errand.slotTaken(), errand.grabberId());
    }
}
