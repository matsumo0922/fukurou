#define _GNU_SOURCE
#include <errno.h>
#include <grp.h>
#include <linux/securebits.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <ftw.h>
#include <sys/stat.h>
#include <linux/capability.h>

#define LLM_UID 10002
#define APP_UID 10001
#define LLM_GID 10004
#define LLM_HOME_ROOT "/run/fukurou/llm-homes/"
#define CANARY_FLAG "/run/secrets/fukurou_canary_enabled"
#define CANARY_CLIENT "/usr/local/libexec/fukurou-mcp-canary-client.mjs"

static void fail(const char *message) {
    fprintf(stderr, "fukurou llm launcher: %s\n", message);
    _exit(126);
}

static int allowed_env(const char *entry) {
    static const char *names[] = {
        "PATH=", "HOME=", "CODEX_HOME=", "CLAUDE_CONFIG_DIR=", "LANG=", "LC_ALL=", "TERM=", "TMPDIR=",
        "XDG_CACHE_HOME=", "FUKUROU_INVOCATION_ID=", "FUKUROU_LLM_PROVIDER=", "FUKUROU_PROMPT_HASH=",
        "FUKUROU_SYSTEM_PROMPT_VERSION=", "FUKUROU_MARKET_SNAPSHOT_ID=", "FUKUROU_RUNTIME_CONFIG_VERSION_ID=",
        "FUKUROU_RUNTIME_CONFIG_HASH=", "FUKUROU_FALSIFIER_INTENT_ID=", "FUKUROU_CANARY_INTENT_ID=",
    };
    for (size_t index = 0; index < sizeof(names) / sizeof(names[0]); index++) {
        if (strncmp(entry, names[index], strlen(names[index])) == 0) return 1;
    }
    return 0;
}

static void require_canary_flag(void) {
    int fd = open(CANARY_FLAG, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) fail("canary mode is disabled");
    struct stat metadata;
    if (fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode) || metadata.st_uid != 0 ||
        (metadata.st_mode & 0777) != 0400 || metadata.st_nlink != 1) fail("canary flag metadata rejected");
    close(fd);
}

static int required_mcp_launcher_capability(int capability) {
    return capability == CAP_CHOWN || capability == CAP_DAC_READ_SEARCH || capability == CAP_SETGID ||
        capability == CAP_SETUID || capability == CAP_SETPCAP;
}

static void restrict_capability_bounding_set(void) {
    for (int capability = 0; capability <= CAP_LAST_CAP; capability++) {
        if (required_mcp_launcher_capability(capability)) continue;
        if (prctl(PR_CAPBSET_DROP, capability, 0, 0, 0) != 0 && errno != EINVAL) {
            fail("cannot restrict capability bounding set");
        }
    }
}

static void verify_canary_security_state(void) {
    uid_t real_uid, effective_uid, saved_uid;
    gid_t real_gid, effective_gid, saved_gid;
    struct rlimit core;
    if (getresuid(&real_uid, &effective_uid, &saved_uid) != 0 ||
        real_uid != LLM_UID || effective_uid != LLM_UID || saved_uid != LLM_UID) fail("canary uid invariant rejected");
    if (getresgid(&real_gid, &effective_gid, &saved_gid) != 0 ||
        real_gid != LLM_GID || effective_gid != LLM_GID || saved_gid != LLM_GID) fail("canary gid invariant rejected");
    if (getgroups(0, NULL) != 0) fail("canary supplementary group invariant rejected");
    if (prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) != 0) fail("canary dumpable invariant rejected");
    if (prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) != 0) fail("canary no_new_privs invariant rejected");
    if (getrlimit(RLIMIT_CORE, &core) != 0 || core.rlim_cur != 0 || core.rlim_max != 0) fail("canary core invariant rejected");
    for (int capability = 0; capability <= CAP_LAST_CAP; capability++) {
        int expected = required_mcp_launcher_capability(capability);
        if (prctl(PR_CAPBSET_READ, capability, 0, 0, 0) != expected) fail("canary capability bounding invariant rejected");
    }
    for (int descriptor = 0; descriptor <= 2; descriptor++) {
        if (fcntl(descriptor, F_GETFD) < 0) fail("canary standard descriptor invariant rejected");
    }
    for (int descriptor = 3; descriptor <= 64; descriptor++) {
        if (fcntl(descriptor, F_GETFD) >= 0 || errno != EBADF) fail("canary inherited descriptor invariant rejected");
    }
}

static int transfer_cleanup_entry(const char *path, const struct stat *metadata, int type, struct FTW *state) {
    (void)metadata;
    (void)type;
    (void)state;
    return lchown(path, APP_UID, LLM_GID);
}

static int delete_cleanup_entry(const char *path, const struct stat *metadata, int type, struct FTW *state) {
    (void)metadata;
    (void)state;
    if (type == FTW_DP) return rmdir(path);
    return unlink(path);
}

static int decimal_suffix(const char *value) {
    if (*value == '\0') return 0;
    for (; *value != '\0'; value++) {
        if (*value < '0' || *value > '9') return 0;
    }
    return 1;
}

static void cleanup_per_run_home(const char *path) {
    if (getuid() != APP_UID) fail("cleanup caller rejected");
    size_t root_length = strlen(LLM_HOME_ROOT);
    if (strncmp(path, LLM_HOME_ROOT, root_length) != 0) fail("cleanup path root rejected");
    const char *name = path + root_length;
    const char *suffix = NULL;
    if (strncmp(name, "fukurou-codex-home-", 19) == 0) suffix = name + 19;
    if (strncmp(name, "fukurou-llm-config-", 19) == 0) suffix = name + 19;
    if (strchr(name, '/') != NULL || suffix == NULL || !decimal_suffix(suffix)) {
        fail("cleanup path name rejected");
    }
    struct stat metadata;
    if (lstat(path, &metadata) != 0 || !S_ISDIR(metadata.st_mode) ||
        metadata.st_uid != APP_UID || metadata.st_gid != LLM_GID) fail("cleanup root metadata rejected");
    if (nftw(path, transfer_cleanup_entry, 32, FTW_PHYS) != 0) fail("cleanup ownership transfer failed");
    if (setgroups(0, NULL) != 0) fail("cleanup cannot clear supplementary groups");
    if (setresgid(LLM_GID, LLM_GID, LLM_GID) != 0) fail("cleanup cannot drop gid");
    if (setresuid(APP_UID, APP_UID, APP_UID) != 0) fail("cleanup cannot drop uid");
    if (nftw(path, delete_cleanup_entry, 32, FTW_DEPTH | FTW_PHYS) != 0) fail("cleanup traversal failed");
    _exit(0);
}

int main(int argc, char **argv, char **envp) {
    if (argc < 2) fail("provider selector is required");
    if (strcmp(argv[1], "cleanup") == 0) {
        if (argc != 3) fail("cleanup mode requires one per-run home path");
        cleanup_per_run_home(argv[2]);
    }
    const char *executable = NULL;
    if (strcmp(argv[1], "claude") == 0) executable = "/usr/local/bin/claude";
    if (strcmp(argv[1], "codex") == 0) executable = "/usr/local/bin/codex";
    int canary = strcmp(argv[1], "canary") == 0;
    if (canary) {
        if (argc != 4) fail("canary mode requires manifest id and phase");
        require_canary_flag();
        executable = "/usr/bin/node";
    }
    if (executable == NULL) fail("unknown provider selector");

    struct rlimit core = {0, 0};
    if (setrlimit(RLIMIT_CORE, &core) != 0) fail("cannot disable core dumps");
    umask(0007);
    if (prctl(PR_SET_SECUREBITS, SECBIT_KEEP_CAPS_LOCKED) != 0) fail("cannot lock capability retention");
    restrict_capability_bounding_set();
    if (setgroups(0, NULL) != 0) fail("cannot clear supplementary groups");
    if (setresgid(LLM_GID, LLM_GID, LLM_GID) != 0) fail("cannot drop gid");
    if (setresuid(LLM_UID, LLM_UID, LLM_UID) != 0) fail("cannot drop uid");
    if (prctl(PR_SET_DUMPABLE, 0) != 0) fail("cannot disable dumpability");
    /* The CLI must be able to invoke the sole fixed-purpose setuid MCP launcher.
       The image removes setuid/setgid from every other executable. */

    size_t count = 0;
    while (envp[count] != NULL) count++;
    char **clean_env = calloc(count + 4, sizeof(char *));
    if (clean_env == NULL) fail("cannot allocate environment");
    size_t output = 0;
    for (size_t index = 0; index < count; index++) {
        if (allowed_env(envp[index])) clean_env[output++] = envp[index];
    }
    clean_env[output] = NULL;
    if (close_range(3, ~0U, 0) != 0) fail("cannot close inherited descriptors");

    if (canary) {
        verify_canary_security_state();
        clean_env[output++] = "FUKUROU_CANARY_LLM_DUMPABLE=0";
        clean_env[output++] = "FUKUROU_CANARY_LLM_CORE_LIMIT=0:0";
        clean_env[output++] = "FUKUROU_CANARY_LLM_LAUNCH_FDS=0,1,2";
        clean_env[output] = NULL;
        char *const canary_args[] = {"node", CANARY_CLIENT, argv[2], argv[3], NULL};
        execve(executable, canary_args, clean_env);
    } else {
        execve(executable, &argv[1], clean_env);
    }
    fail(strerror(errno));
}
