package xyz.nim.modDetectorPlugin.catalog;

public enum DetectionScope {
    PRIMARY,
    FULL;

    public DetectionScope escalated() {
        return this == PRIMARY ? FULL : this;
    }
}
