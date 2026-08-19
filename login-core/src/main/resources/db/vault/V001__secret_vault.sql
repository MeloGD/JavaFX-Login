-- The SecretVault: what a ProtectedFeature must keep secret, and the wrapped copies of the one key
-- that opens it.
--
-- A separate file from the CredentialStore by ADR-0004, and separately versioned with it. The two
-- answer different questions, face different attackers and change at different rates; nothing here
-- is a table that could have lived beside the accounts.
--
-- No foreign key names an Account, because the Accounts are in the other file. What keeps the two in
-- step is the AuthenticationService: it destroys an Operator's wrap when it deletes their Account,
-- and it wraps afresh whenever somebody enrols. The absence of a constraint is therefore deliberate
-- and is not an oversight — a wrap left behind by a delete that half-worked would be Vault access
-- reachable by recreating a name, which is why the delete destroys the wrap first.

-- One row per Operator: the DataKey, encrypted under a key derived from that Operator's password.
--
-- The KDF's salt and its cost parameters live here rather than being read from the authentication
-- hash, and that separation is the load-bearing idea of ADR-0004. The stored authentication hash is
-- never key material: the Vault opens because the password derives, through Argon2id with the salt
-- and parameters in this row, the key that unwraps the DataKey below. A patched binary flipping a
-- boolean gets a Session and no key.
--
-- Parameters are per row so that raising them later is something a rewrap does one Operator at a
-- time, exactly as the PHC string lets the authentication hash be raised.
CREATE TABLE data_key_wraps (
    account_name     TEXT PRIMARY KEY,

    kdf_salt         BLOB NOT NULL,
    kdf_memory_kib   INTEGER NOT NULL,
    kdf_iterations   INTEGER NOT NULL,
    kdf_parallelism  INTEGER NOT NULL,

    -- AES-256-GCM: the nonce is fresh for every wrap ever written, and the tag travels on the end of
    -- the ciphertext. A wrong password fails the tag, which is how a wrong password is told from a
    -- right one without anything here having to be compared against anything.
    nonce            BLOB NOT NULL,
    wrapped_data_key BLOB NOT NULL,

    wrapped_at       TEXT NOT NULL
);

-- The second copy of the DataKey, under the MachineKey, which lives in a file beside this one.
--
-- Exactly one row, enforced here rather than only in Java. This copy is what lets the service
-- provision an Operator or rewrap after a reset with nobody present and without ever showing the
-- DataKey to the Administrator. ADR-0005 is honest about the size of that: it is also what makes the
-- Administrator's exclusion from the Vault least privilege rather than a boundary, and the project
-- chose it over a Vault that becomes unrecoverable the day every Operator forgets their password.
CREATE TABLE machine_wrap (
    id               INTEGER PRIMARY KEY CHECK (id = 1),
    nonce            BLOB NOT NULL,
    wrapped_data_key BLOB NOT NULL,
    wrapped_at       TEXT NOT NULL
);

-- The secrets themselves, one row each, each under a key derived from the DataKey for that name
-- alone. Deriving per name rather than encrypting everything under the DataKey directly is what
-- makes a row moved from one name to another fail to decrypt, and it is what keeps the DataKey
-- itself off every ciphertext in the file.
--
-- A secret is decrypted at the moment it is asked for and never before, so the plaintext window is
-- one request rather than the whole Session.
CREATE TABLE secrets (
    name       TEXT PRIMARY KEY,
    nonce      BLOB NOT NULL,
    ciphertext BLOB NOT NULL,
    kept_at    TEXT NOT NULL
);
