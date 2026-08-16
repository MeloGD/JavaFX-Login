-- How an Operator comes to have a password without the Administrator ever choosing it.
--
-- Until now every Account had a password from the moment it existed, because the only Account this
-- build could make was the Administrator's own at the first run. An Operator is made by somebody
-- else, and ASVS 5.0 §6.4.6 says that somebody must not choose the password: the Account is created
-- with none, and with a one-time secret that lets the person who will use it choose their own.
--
-- That is why the accounts table is rebuilt here rather than extended. password_hash has been NOT
-- NULL since V001, and an Account awaiting enrolment has no password at all — not an empty one, not
-- a placeholder one, and above all not a hash of something the Administrator picked. SQLite cannot
-- drop a NOT NULL, so the table is written again with the column as it now has to be and the rows
-- are carried across by name.
CREATE TABLE accounts_awaiting_rename (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL UNIQUE,
    role          TEXT NOT NULL CHECK (role IN ('ADMINISTRATOR', 'OPERATOR')),

    -- Absent exactly while the Account is awaiting enrolment: before anybody has enrolled against
    -- it, and from the moment an Administrator initiates a reset. A reset that left the old hash
    -- here until the new password arrived would be a reset that can be quietly undone, which is the
    -- half-measure the ticket exists to refuse.
    password_hash TEXT,

    second_factor TEXT,

    created_at    TEXT NOT NULL,

    password_strength TEXT NOT NULL DEFAULT 'WEAK'
        CHECK (password_strength IN ('WEAK', 'ACCEPTABLE', 'STRONG')),

    failed_authentications INTEGER NOT NULL DEFAULT 0
        CHECK (failed_authentications >= 0),

    refused_until TEXT,

    -- The one-time enrolment secret, as a SHA-256 of the 128 bits the service generated. Never the
    -- secret itself: it is shown once, on the screen of whoever asked for it, and nothing that
    -- survives that moment can produce it again. A fast hash is the right one here and Argon2id is
    -- not — see EnrolmentSecret, which says why at length.
    enrolment_secret_hash TEXT,

    -- When that secret was issued. How long it stays usable is configuration below, read again on
    -- every decision, so this column records the fact and never the policy.
    enrolment_issued_at TEXT,

    -- When an Administrator last took this Account's password away, until the Operator has been
    -- told about it at their next successful login. Cleared once it has been said, because it is
    -- news and not a property.
    password_reset_at TEXT,

    -- An Account has a password or an outstanding enrolment, and never both or neither. Both would
    -- be an Account whose old password still works while a secret to replace it is in the post;
    -- neither would be an Account nobody can ever use and no Administrator can rescue. Written here
    -- rather than only in Java, for the reason the single-Administrator index is: the rule outlives
    -- whichever code is on top of the file.
    CHECK ((password_hash IS NULL) <> (enrolment_secret_hash IS NULL)),

    -- A secret is always dated, so that whether it has expired is never a question this schema
    -- cannot answer.
    CHECK ((enrolment_secret_hash IS NULL) = (enrolment_issued_at IS NULL))
);

INSERT INTO accounts_awaiting_rename
    (id, name, role, password_hash, second_factor, created_at,
     password_strength, failed_authentications, refused_until)
SELECT id, name, role, password_hash, second_factor, created_at,
       password_strength, failed_authentications, refused_until
FROM accounts;

DROP TABLE accounts;

ALTER TABLE accounts_awaiting_rename RENAME TO accounts;

-- Recreated because it went with the table it was on: exactly one Administrator, enforced by the
-- schema rather than only by the code above it.
CREATE UNIQUE INDEX one_administrator ON accounts (role) WHERE role = 'ADMINISTRATOR';

-- How long an enrolment secret stays usable, as an ISO-8601 duration. Three days: long enough that
-- an Administrator can hand one over in person to somebody who is away for a weekend, short enough
-- that a code written on a sticky note is worth nothing by the time the note is found. An
-- Administrator re-issues one that ran out, so the wrong guess here costs a conversation and never
-- an Account.
INSERT INTO configuration (name, value) VALUES ('enrolment.secret_lasts_for', 'PT72H');
