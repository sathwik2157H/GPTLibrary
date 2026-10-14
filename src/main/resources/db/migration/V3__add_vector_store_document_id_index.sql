CREATE INDEX IF NOT EXISTS idx_vector_store_document_id 
ON vector_store ((metadata->>'document_id'));
