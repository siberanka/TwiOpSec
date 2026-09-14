package com.siberanka.twiopsec.config;

public record ImportReport(Status status, int operators, int permissionHolders, int permissions, String detail) {
    public enum Status {
        IMPORTED,
        ALREADY_IMPORTED,
        NOT_FOUND,
        FAILED
    }

    public static ImportReport notFound() {
        return new ImportReport(Status.NOT_FOUND, 0, 0, 0, "legacy folder not found");
    }
}
