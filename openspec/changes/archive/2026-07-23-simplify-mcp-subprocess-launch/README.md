# simplify-mcp-subprocess-launch

Remove fd-passing, the setuid MCP/LLM launchers, and the runtime supervisor; launch the MCP subprocess with argv + literal env + ordinary file reads (issue #288).
