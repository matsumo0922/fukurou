#define _GNU_SOURCE

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>

#include "fukurou-runtime-proxy.h"

#define CANARY_FLAG "/run/secrets/fukurou_canary_enabled"
#define CANARY_CLIENT "/usr/local/libexec/fukurou-mcp-canary-client.mjs"

static void fail(const char *message) {
    fprintf(stderr, "fukurou llm launcher: %s\n", message);
    _exit(126);
}

static int lowercase_hex(const char *value, size_t expected_length) {
    if (value == NULL || strlen(value) != expected_length) return 0;
    for (const char *cursor = value; *cursor != '\0'; cursor++) {
        if (!(isdigit((unsigned char)*cursor) || (*cursor >= 'a' && *cursor <= 'f'))) return 0;
    }
    return 1;
}

static int allowed_environment(const char *entry) {
    static const char *names[] = {
        "PATH=", "HOME=", "CODEX_HOME=", "CLAUDE_CONFIG_DIR=", "LANG=", "LC_ALL=", "TERM=", "TMPDIR=",
        "XDG_CACHE_HOME=", "FUKUROU_INVOCATION_ID=", "FUKUROU_LLM_PROVIDER=", "FUKUROU_PROMPT_HASH=",
        "FUKUROU_SYSTEM_PROMPT_VERSION=", "FUKUROU_MARKET_SNAPSHOT_ID=", "FUKUROU_RUNTIME_CONFIG_VERSION_ID=",
        "FUKUROU_RUNTIME_CONFIG_HASH=", "FUKUROU_FALSIFIER_INTENT_ID=", "FUKUROU_CANARY_INTENT_ID=",
        "FUKUROU_CANARY_MCP_FIXTURE=",
    };
    for (size_t index = 0; index < sizeof(names) / sizeof(names[0]); index++) {
        if (strncmp(entry, names[index], strlen(names[index])) == 0) return 1;
    }
    return 0;
}

static void require_canary_flag(void) {
    struct stat metadata;
    if (lstat(CANARY_FLAG, &metadata) != 0 || !S_ISREG(metadata.st_mode) || metadata.st_uid != 0 ||
        (metadata.st_mode & 0777) != 0400 || metadata.st_nlink != 1) fail("canary mode is disabled");
}

static void cleanup_per_run_home(const char *path) {
    /* Cleanup is a typed PID1 request. This proxy never opens or deletes a path. */
    _exit(fukurou_supervisor_cleanup_proxy(path));
}

int main(int argc, char **argv, char **envp) {
    if (argc < 2) fail("provider selector is required");
    if (strcmp(argv[1], "cleanup") == 0) {
        if (argc != 3) fail("cleanup mode requires one per-run home path");
        cleanup_per_run_home(argv[2]);
    }

    enum fukurou_launch_profile profile;
    if (strcmp(argv[1], "claude") == 0) profile = FUKUROU_PROFILE_CLAUDE_CURRENT_V1;
    else if (strcmp(argv[1], "codex") == 0) profile = FUKUROU_PROFILE_CODEX_CURRENT_V1;
    else if (strcmp(argv[1], "canary") == 0) profile = FUKUROU_PROFILE_FOUNDATION_CANARY_V1;
    else fail("unknown provider selector");

    if (profile == FUKUROU_PROFILE_FOUNDATION_CANARY_V1) {
        int valid_phase = argc == 4 && (strcmp(argv[3], "PROPOSER") == 0 || strcmp(argv[3], "FALSIFIER") == 0);
        int valid_rro = argc == 5 && strcmp(argv[3], "RISK_REDUCTION_ONLY") == 0 &&
            (strcmp(argv[4], "NO_TRADE") == 0 || strcmp(argv[4], "EXIT") == 0 ||
             strcmp(argv[4], "REDUCE") == 0 || strcmp(argv[4], "ADJUST_PROTECTION") == 0 ||
             strcmp(argv[4], "ENTER") == 0 || strcmp(argv[4], "ADD_LONG") == 0);
        if (!lowercase_hex(argv[2], 48) || (!valid_phase && !valid_rro)) fail("canary manifest or phase rejected");
        require_canary_flag();
    }

    umask(0007);
    size_t environment_count = 0;
    while (envp[environment_count] != NULL) environment_count++;
    char **clean_environment = calloc(environment_count + 4, sizeof(char *));
    if (clean_environment == NULL) fail("cannot allocate environment");
    size_t output = 0;
    for (size_t index = 0; index < environment_count; index++) {
        if (allowed_environment(envp[index])) clean_environment[output++] = envp[index];
    }
    if (profile == FUKUROU_PROFILE_FOUNDATION_CANARY_V1) {
        clean_environment[output++] = "FUKUROU_CANARY_LLM_DUMPABLE=0";
        clean_environment[output++] = "FUKUROU_CANARY_LLM_CORE_LIMIT=0:0";
        clean_environment[output++] = "FUKUROU_CANARY_LLM_LAUNCH_FDS=0,1,2";
    }
    clean_environment[output] = NULL;
    if (close_range(3, ~0U, 0) != 0) fail("cannot close inherited descriptors");

    if (profile == FUKUROU_PROFILE_FOUNDATION_CANARY_V1) {
        char *const arguments[] = {"node", CANARY_CLIENT, argv[2], argv[3], argc == 5 ? argv[4] : NULL, NULL};
        _exit(fukurou_supervisor_proxy(profile, arguments, clean_environment, 3));
    }
    _exit(fukurou_supervisor_proxy(profile, &argv[1], clean_environment, 3));
}
