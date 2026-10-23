-- Servers that receive the user's own login token, e.g. the booking backend's MCP server
ALTER TABLE mcp_server ADD COLUMN forward_user_token BOOLEAN NOT NULL DEFAULT FALSE;
