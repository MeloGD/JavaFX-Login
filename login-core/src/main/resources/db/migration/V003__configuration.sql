-- The configuration of the application, which lives in the same file as the Accounts because the
-- same process owns both and neither may be edited by the account the graphical client runs as.
--
-- Named settings rather than one column per setting: what a deployment can configure grows a ticket
-- at a time, and a table that grows a row instead of a column keeps those upgrades to inserts.
CREATE TABLE configuration (
    name  TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- How long a Session may go without Operator activity, as an ISO-8601 duration, or the literal
-- 'disabled' for a kiosk deployment. Written here rather than defaulted in code, so that what the
-- service reads is always something an Administrator could have written — see InactivityPeriod.
INSERT INTO configuration (name, value) VALUES ('session.inactivity_period', 'PT15M');
