CREATE TABLE import_observation_reservation (
    delivery_id TEXT PRIMARY KEY,
    reserved_at_ms INTEGER NOT NULL CHECK (reserved_at_ms >= 0),
    FOREIGN KEY (delivery_id) REFERENCES import_delivery(delivery_id) ON DELETE CASCADE
);
