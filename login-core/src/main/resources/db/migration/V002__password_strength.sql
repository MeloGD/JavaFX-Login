-- The coarse PasswordStrength band of each Account's password.
--
-- A band and never a score. The estimate is made from a precise number, and keeping that number
-- here would rank every Account by how cheap it is to attack, which is exactly the list an attacker
-- who reached this file would otherwise have to build.
--
-- Rows written before this column existed default to the weakest band rather than to a guess: the
-- band cannot be recovered from a hash, and an unknown password must not be displayed as a strong
-- one to the Administrator deciding whom to nudge.
ALTER TABLE accounts
    ADD COLUMN password_strength TEXT NOT NULL DEFAULT 'WEAK'
    CHECK (password_strength IN ('WEAK', 'ACCEPTABLE', 'STRONG'));
