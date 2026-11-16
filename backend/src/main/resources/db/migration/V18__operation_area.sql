-- ADR-025: operasyon bölgesi (merkez + isteğe bağlı yarıçap). Grup buluşma noktası koordinatları V4'te var.
ALTER TABLE organization.operations
    ADD COLUMN center_lat double precision,
    ADD COLUMN center_lon double precision,
    ADD COLUMN radius_m   integer;

ALTER TABLE organization.operations ADD CONSTRAINT ck_operations_area CHECK (
    (center_lat IS NULL) = (center_lon IS NULL)
    AND (center_lat IS NULL OR center_lat BETWEEN -90 AND 90)
    AND (center_lon IS NULL OR center_lon BETWEEN -180 AND 180)
    AND (radius_m IS NULL OR (center_lat IS NOT NULL AND radius_m BETWEEN 50 AND 200000)));
