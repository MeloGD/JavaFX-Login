-- What the AuthenticationService remembers about an Account's failed authentications, so that
-- guessing at the login screen is not free.
--
-- It lives here, in the file the service owns, rather than in memory: the service stops after five
-- idle minutes, so state held in the process is state an attacker clears by waiting, and a file
-- beside the store is one an Operator cannot delete. Both columns sit on the Account they are about
-- because a Lockout has no existence apart from one — nothing here ever remembers a name that was
-- typed at the screen and belongs to nobody.
ALTER TABLE accounts
    ADD COLUMN failed_authentications INTEGER NOT NULL DEFAULT 0
    CHECK (failed_authentications >= 0);

-- The moment the Account stops being refused, or NULL where it is not being refused at all.
--
-- An ISO-8601 instant in UTC, not the local offset created_at uses: this one is read back by the
-- service and compared against the machine's clock, so it is written in the one form that means the
-- same thing after the machine has moved timezone.
ALTER TABLE accounts
    ADD COLUMN refused_until TEXT;

-- The LockoutPolicy: how many failed authentications in a row produce a Lockout, and how long that
-- Lockout lasts. Written here rather than defaulted in code, for the reason V003 gives — what the
-- service reads is always something a deployment could have written.
INSERT INTO configuration (name, value) VALUES ('lockout.failures_that_lock', '5');
INSERT INTO configuration (name, value) VALUES ('lockout.lasts_for', 'PT15M');
