package com.iocextractor.adapter.out.store.jdbc;

/** Keeps fatal cleanup failures visible while attempting every independently owned release. */
final class DocumentWorkspaceFailures {
    private DocumentWorkspaceFailures() { }

    static Throwable accumulate(Throwable primary, Throwable next) {
        if (primary == null || primary == next) { return next; }
        if (next instanceof Error && !(primary instanceof Error)) {
            next.addSuppressed(primary);
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }
}
