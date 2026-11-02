package com.campusdash.domain.grab.model;

/** 请求终态不能退回 PROCESSING。 */
public enum GrabRequestStatus {
    PROCESSING, SUCCEEDED, REJECTED, EXPIRED;
    public boolean terminal() { return this != PROCESSING; }
}
