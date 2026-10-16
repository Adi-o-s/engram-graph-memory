-- Trigram index so substring keyword matching (ILIKE '%term%') stays index-assisted.
-- pg_trgm is a trusted extension (PostgreSQL 13+), so the database owner can create it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_memories_search_text_trgm ON memories USING gin (search_text gin_trgm_ops);
