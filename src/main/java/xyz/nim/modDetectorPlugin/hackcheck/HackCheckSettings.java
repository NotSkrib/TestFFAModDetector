package xyz.nim.modDetectorPlugin.hackcheck;

// Replaces HackCheckManager's seven positional configure(...) arguments. Every knob the manager
// needs comes from hack-checks: in config.yml, so adding one no longer means growing an
// already-unreadable call site and a matching parameter list.
public record HackCheckSettings(boolean kick, String kickMessage, int timeoutTicks,
        int betweenBatchTicks, boolean debug, boolean skipBedrock, String bedrockNamePrefix,
        boolean escalate, int batchSize, int keysPerLine) {
}
