INSERT INTO users (full_name, email) VALUES ('Alice Johnson', 'alice@example.com') ON CONFLICT DO NOTHING;
INSERT INTO users (full_name, email) VALUES ('Bob Smith', 'bob@example.com') ON CONFLICT DO NOTHING;
INSERT INTO users (full_name, email) VALUES ('Charlie Brown', 'charlie@example.com') ON CONFLICT DO NOTHING;
