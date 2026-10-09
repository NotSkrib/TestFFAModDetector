package com.notskrib.moddetector.catalog;

public enum DetectionScope {
    PRIMARY,
    FULL;

    public DetectionScope escalated() {
        return this == PRIMARY ? FULL : this;
    }
}
