-- ShedLock table — harmless when scheduler.distributed=false.
-- ShedLock will use this table to coordinate sweepers across replicas.
CREATE TABLE IF NOT EXISTS shedlock (
  name VARCHAR(64) NOT NULL,
  lock_until TIMESTAMP(3) NOT NULL,
  locked_at TIMESTAMP(3) NOT NULL,
  locked_by VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
);

-- Seat reservation seed data --------------------------------------------------
INSERT INTO seats (id, code, type, resource_id, status, version) VALUES
  (1, '12A', 'FLIGHT_SEAT', 'VN201', 'AVAILABLE', 0),
  (2, '12B', 'FLIGHT_SEAT', 'VN201', 'AVAILABLE', 0),
  (3, '12C', 'FLIGHT_SEAT', 'VN201', 'AVAILABLE', 0),
  (4, '14F', 'FLIGHT_SEAT', 'VN201', 'AVAILABLE', 0),
  (5, '14G', 'FLIGHT_SEAT', 'VN201', 'AVAILABLE', 0),
  (10, '301', 'HOTEL_ROOM', 'HANOI-DELUXE', 'AVAILABLE', 0),
  (11, '302', 'HOTEL_ROOM', 'HANOI-DELUXE', 'AVAILABLE', 0),
  (12, '303', 'HOTEL_ROOM', 'HANOI-DELUXE', 'AVAILABLE', 0),
  (13, '401', 'HOTEL_ROOM', 'HANOI-DELUXE', 'AVAILABLE', 0);

-- Calendar booking seed data ---------------------------------------------------
INSERT INTO calendar_owners (id, display_name, timezone, buffer_minutes) VALUES
  ('andre', 'Andre Tu', 'Asia/Ho_Chi_Minh', 5),
  ('alice', 'Alice (NYC)', 'America/New_York', 10),
  ('koji',  'Koji (Tokyo)', 'Asia/Tokyo', 0);

-- Andre: Mon-Fri, 09:00 - 17:00, every week from 2026-01-01
INSERT INTO recurring_rules (owner_id, day_of_week, start_local, end_local, valid_from, valid_until) VALUES
  ('andre', 1, '09:00:00', '17:00:00', '2026-01-01', NULL),
  ('andre', 2, '09:00:00', '17:00:00', '2026-01-01', NULL),
  ('andre', 3, '09:00:00', '17:00:00', '2026-01-01', NULL),
  ('andre', 4, '09:00:00', '17:00:00', '2026-01-01', NULL),
  ('andre', 5, '09:00:00', '17:00:00', '2026-01-01', NULL),
  ('alice', 1, '10:00:00', '16:00:00', '2026-01-01', NULL),
  ('alice', 3, '10:00:00', '16:00:00', '2026-01-01', NULL),
  ('alice', 5, '10:00:00', '16:00:00', '2026-01-01', NULL),
  ('koji',  2, '13:00:00', '18:00:00', '2026-01-01', NULL),
  ('koji',  4, '13:00:00', '18:00:00', '2026-01-01', NULL);

-- Overbooking seed data --------------------------------------------------------
INSERT INTO flights (code, origin, destination, capacity, no_show_rate, overbook_factor, departure_time, version) VALUES
  ('VN201', 'HAN', 'SGN', 10, 0.1000, 0.2000, '2026-07-01T08:00:00Z', 0),
  ('VN310', 'SGN', 'DAD', 20, 0.0800, 0.1500, '2026-07-02T10:30:00Z', 0),
  ('VN999', 'HAN', 'NRT', 30, 0.0500, 0.1000, '2026-07-03T22:15:00Z', 0);
