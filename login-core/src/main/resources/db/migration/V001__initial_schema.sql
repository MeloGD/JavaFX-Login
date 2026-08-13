-- Accounts and their password hashes.
--
-- The hash is a PHC string, so the salt and the Argon2id cost parameters travel inside it and can
-- be raised later per Account without invalidating the ones already stored.
CREATE TABLE accounts (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL UNIQUE,
    role          TEXT NOT NULL CHECK (role IN ('ADMINISTRATOR', 'OPERATOR')),
    password_hash TEXT NOT NULL,

    -- Reserved for a future SecondFactor. TOTP is deferred from v1 and nothing writes this column
    -- yet; it exists now so that adding a second factor is a migration about behaviour rather than
    -- about shape.
    second_factor TEXT,

    created_at    TEXT NOT NULL
);

-- Exactly one Administrator, enforced by the schema rather than only by the code above it.
CREATE UNIQUE INDEX one_administrator ON accounts (role) WHERE role = 'ADMINISTRATOR';
