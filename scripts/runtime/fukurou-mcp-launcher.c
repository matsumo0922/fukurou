#define _GNU_SOURCE

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#include "fukurou-runtime-proxy.h"

static void fail(const char *message) {
    fprintf(stderr, "fukurou mcp launcher: %s\n", message);
    _exit(126);
}

static int valid_id(const char *id) {
    if (id == NULL || strlen(id) != 48) return 0;
    for (size_t index = 0; index < 48; index++) {
        if (!(isdigit((unsigned char)id[index]) || (id[index] >= 'a' && id[index] <= 'f'))) return 0;
    }
    return 1;
}

int main(int argc, char **argv, char **envp) {
    if (argc != 2 || !valid_id(argv[1])) fail("canonical manifest id is required");
    const char *invocation_id = getenv("FUKUROU_INVOCATION_ID");
    if (invocation_id == NULL || strlen(invocation_id) == 0 || strlen(invocation_id) > 128) {
        fail("invocation id is required");
    }
    for (const char *cursor = invocation_id; *cursor != '\0'; cursor++) {
        if (!(isalnum((unsigned char)*cursor) || *cursor == '.' || *cursor == '_' || *cursor == ':' || *cursor == '-')) {
            fail("invocation id is malformed");
        }
    }
    umask(0007);
    char invocation_environment[160];
    if (snprintf(invocation_environment, sizeof(invocation_environment), "FUKUROU_INVOCATION_ID=%s", invocation_id) >=
        (int)sizeof(invocation_environment)) fail("invocation id is too long");
    char manifest_environment[96];
    if (snprintf(manifest_environment, sizeof(manifest_environment), "FUKUROU_MCP_MANIFEST_ID=%s", argv[1]) >=
        (int)sizeof(manifest_environment)) fail("manifest id is too long");
    const char *canary_fixture_value = getenv("FUKUROU_CANARY_MCP_FIXTURE");
    const int canary_fixture = canary_fixture_value != NULL && strcmp(canary_fixture_value, "1") == 0;
    char *arguments[8] = {"java"};
    size_t argument_count = 1;
    if (canary_fixture) arguments[argument_count++] = "-Dfukurou.mcp.testInMemoryRuntime=true";
    arguments[argument_count++] = "--add-opens=java.base/java.io=ALL-UNNAMED";
    arguments[argument_count++] = "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED";
    arguments[argument_count++] = "-jar";
    arguments[argument_count++] = "/app/fukurou-mcp-all.jar";
    arguments[argument_count] = NULL;
    char *environment[6] = {"PATH=/opt/java/openjdk/bin:/usr/bin:/bin", invocation_environment, manifest_environment};
    if (canary_fixture) {
        environment[3] = "FUKUROU_CANARY_MCP_FIXTURE=1";
        environment[4] = "FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME=true";
    }
    (void)envp;
    /* PID1 opens the root-only password and manifest, creates sealed FD 3/4,
       and connects FD 5 with the APP filesystem identity. The proxy keeps
       this #223 role ordering opaque to the caller. */
    _exit(fukurou_supervisor_proxy(FUKUROU_PROFILE_MCP_CURRENT_V1, arguments, environment, 3));
}
