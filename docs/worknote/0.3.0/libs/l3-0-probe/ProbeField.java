package qualification.logging;

/** An unrelated catalog substituted only at the old enum boundary. */
record ProbeField(String key, LogValueType valueType) {
    static final ProbeField EVENT_ACTION = new ProbeField("event.action", LogValueType.STRING);
    static final ProbeField EVENT_OUTCOME = new ProbeField("event.outcome", LogValueType.STRING);
    static final ProbeField EVENT_DURATION = new ProbeField("event.duration", LogValueType.LONG);
    static final ProbeField TASK = new ProbeField("collector.task.id", LogValueType.STRING);
    static final ProbeField ITEMS = new ProbeField("collector.items", LogValueType.LONG);
    static final ProbeField RETRY = new ProbeField("collector.retry", LogValueType.BOOLEAN);
    static final ProbeField TOKEN = new ProbeField("collector.token", LogValueType.STRING);
}

/** The IOC action catalog is deliberately absent from the fixture. */
enum EventAction {
    COLLECT_COMPLETE;

    public String value() {
        return "collect_complete";
    }
}
