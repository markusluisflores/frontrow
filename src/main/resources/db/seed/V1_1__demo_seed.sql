-- Demo seed (plan 4B decision 2), applied only by the demo profile (application.yml). Fixed dates: every event starts
-- in 2030 or 2031 and its sales opened on 2026-01-01, so the demo stays on sale for years and reads no clock. Each
-- event's sales close two hours before it starts (spec §5: sales_close_at <= starts_at; SeedDataTest checks it).

INSERT INTO venue (name) VALUES ('Riverside Hall'), ('The Lantern Room');

INSERT INTO seat (venue_id, section, row_label, seat_number)
SELECT v.id, layout.section, layout.row_label, n
FROM venue v
JOIN (VALUES
        ('Riverside Hall', 'Floor', 'A', 10),
        ('Riverside Hall', 'Floor', 'B', 10),
        ('Riverside Hall', 'Balcony', 'A', 6),
        ('The Lantern Room', 'Stalls', 'A', 8),
        ('The Lantern Room', 'Stalls', 'B', 8)
    ) AS layout (venue, section, row_label, seats) ON layout.venue = v.name
CROSS JOIN LATERAL generate_series(1, layout.seats) AS n;

INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
SELECT v.id, e.name, e.starts_at::timestamptz, '2026-01-01T00:00:00Z'::timestamptz,
       e.starts_at::timestamptz - interval '2 hours', 'ON_SALE', 'CAD'
FROM (VALUES
        ('Night Owls Live', 'Riverside Hall', '2030-06-14T19:00:00Z'),
        ('Quiet Strings Quartet', 'The Lantern Room', '2030-09-05T18:30:00Z'),
        ('Synth City', 'Riverside Hall', '2031-02-20T20:00:00Z')
    ) AS e (name, venue, starts_at)
JOIN venue v ON v.name = e.venue;

INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents)
SELECT e.id, s.id, s.venue_id, p.price_cents
FROM event e
JOIN seat s ON s.venue_id = e.venue_id
JOIN (VALUES
        ('Night Owls Live', 'Floor', 6500),
        ('Night Owls Live', 'Balcony', 4500),
        ('Quiet Strings Quartet', 'Stalls', 3800),
        ('Synth City', 'Floor', 7200),
        ('Synth City', 'Balcony', 5000)
    ) AS p (event, section, price_cents) ON p.event = e.name AND p.section = s.section;
