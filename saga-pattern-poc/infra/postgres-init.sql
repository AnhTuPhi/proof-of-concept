-- Bootstrap databases for Temporal alongside the saga application DB.
-- Flyway in each service handles schema creation inside the 'saga' database.

CREATE DATABASE temporal;
CREATE DATABASE temporal_visibility;
