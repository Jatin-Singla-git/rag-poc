-- Runs once, only when the data volume is first created. POSTGRES_DB (ragdb) is the default database.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
