#define _GNU_SOURCE
#include "fukurou-runtime-protocol.h"

#include <errno.h>
#include <ctype.h>
#include <dirent.h>
#include <fcntl.h>
#include <grp.h>
#include <poll.h>
#include <openssl/sha.h>
#include <openssl/evp.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define APP_UID 10001
#define APP_GID 10004
#define LLM_UID 10002
#define LLM_GID 10004
#define MCP_UID 10003
#define MCP_GID 10003
#define CONTROL_SOCKET "/run/fukurou/control/supervisor-control.sock"
#define FENCE_DIRECTORY "/var/lib/fukurou/launch-fence"
#define FENCE_FILE FENCE_DIRECTORY "/fence-v1"
#define FENCE_HASH_FILE FENCE_DIRECTORY "/fence-v1.sha256"
#define MAX_JOBS 64

struct launch_request {
    struct fukurou_launch_header header;
    char payload[FUKUROU_PROTOCOL_MAX_PAYLOAD];
    int descriptors[16];
    uint16_t descriptor_count;
};

struct job {
    pid_t pid;
    int response_fd;
};

static struct job jobs[MAX_JOBS];
static unsigned char recent_nonces[128][FUKUROU_PROTOCOL_NONCE_SIZE];
static size_t recent_nonce_cursor = 0;
static unsigned long long fence_generation = 0;
static int launches_enabled = 0;
static char fence_state[48] = "CORRUPT";
static int launch_listener = -1;

static int read_database_state(unsigned long long *generation, int *maintenance_enabled, unsigned *active_registrations);
static int reconcile_database(void);

static const char *environment_value(char **environment, const char *name) {
    size_t name_length = strlen(name);
    for (size_t index = 0; environment[index] != NULL; index++) {
        if (strncmp(environment[index], name, name_length) == 0 && environment[index][name_length] == '=') {
            return environment[index] + name_length + 1;
        }
    }
    return NULL;
}

static int read_process_start_ticks(pid_t pid, unsigned long long *start_ticks) {
    char path[64], contents[4096];
    snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    ssize_t length = read(fd, contents, sizeof(contents) - 1);
    close(fd);
    if (length <= 0) return -1;
    contents[length] = '\0';
    char *cursor = strrchr(contents, ')');
    if (cursor == NULL || cursor[1] != ' ') return -1;
    cursor += 2;
    for (unsigned field = 3; field <= 22; field++) {
        char *end = strchr(cursor, ' ');
        if (field == 22) {
            char *parse_end = NULL;
            errno = 0;
            unsigned long long value = strtoull(cursor, &parse_end, 10);
            if (errno != 0 || parse_end == cursor || value == 0) return -1;
            *start_ticks = value;
            return 0;
        }
        if (end == NULL) return -1;
        cursor = end + 1;
    }
    return -1;
}

static int deterministic_uuid(const char *namespace_prefix, const char *invocation_id, const char *role, char output[37]) {
    char identity[384];
    int identity_length = role == NULL
        ? snprintf(identity, sizeof(identity), "%s%s", namespace_prefix, invocation_id)
        : snprintf(identity, sizeof(identity), "%s%s:%s", namespace_prefix, invocation_id, role);
    if (identity_length <= 0 || (size_t)identity_length >= sizeof(identity)) return -1;
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned digest_length = 0;
    if (EVP_Digest(identity, (size_t)identity_length, digest, &digest_length, EVP_md5(), NULL) != 1 || digest_length != 16) return -1;
    int written = snprintf(
        output,
        37,
        "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
        digest[0], digest[1], digest[2], digest[3], digest[4], digest[5], digest[6], digest[7],
        digest[8], digest[9], digest[10], digest[11], digest[12], digest[13], digest[14], digest[15]
    );
    return written == 36 ? 0 : -1;
}

static int register_spawn_receipt(const char *role, const char *invocation_id, pid_t pid) {
    const char *container_instance = getenv("HOSTNAME");
    if (container_instance == NULL || invocation_id == NULL) return -1;
    char namespace_path[64];
    snprintf(namespace_path, sizeof(namespace_path), "/proc/%ld/ns/pid", (long)pid);
    struct stat namespace_metadata;
    unsigned long long start_ticks = 0;
    if (stat(namespace_path, &namespace_metadata) != 0 || namespace_metadata.st_ino == 0 ||
        read_process_start_ticks(pid, &start_ticks) != 0) return -1;

    char registration_id[37], reservation_id[37];
    if (deterministic_uuid("fukurou-pid-registration-v1:", invocation_id, role, registration_id) != 0 ||
        deterministic_uuid("fukurou-launch-reservation-v1:", invocation_id, NULL, reservation_id) != 0) return -1;
    char namespace_value[32], pid_value[32], start_value[32];
    snprintf(namespace_value, sizeof(namespace_value), "%llu", (unsigned long long)namespace_metadata.st_ino);
    snprintf(pid_value, sizeof(pid_value), "%ld", (long)pid);
    snprintf(start_value, sizeof(start_value), "%llu", start_ticks);
    pid_t helper = fork();
    if (helper < 0) return -1;
    if (helper == 0) {
        if (setgroups(0, NULL) != 0 || setresgid(APP_GID, APP_GID, APP_GID) != 0 ||
            setresuid(APP_UID, APP_UID, APP_UID) != 0) _exit(126);
        close_range(3, ~0U, 0);
        char *const arguments[] = {
            "java", "-cp", "/app/app.jar", "me.matsumo.fukurou.PidRegistrationReceiptMain",
            (char *)role, registration_id, reservation_id, (char *)invocation_id, (char *)container_instance,
            namespace_value, pid_value, start_value, NULL,
        };
        extern char **environ;
        execve("/opt/java/openjdk/bin/java", arguments, environ);
        _exit(126);
    }
    int status = 0;
    if (waitpid(helper, &status, 0) != helper) return -1;
    return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
}

static void fatal(const char *message) {
    fprintf(stderr, "fukurou runtime supervisor: %s: %s\n", message, strerror(errno));
    _exit(125);
}

static void sha256_hex(const char *value, size_t length, char output[65]) {
    unsigned char digest[SHA256_DIGEST_LENGTH];
    SHA256((const unsigned char *)value, length, digest);
    for (size_t index = 0; index < sizeof(digest); index++) snprintf(output + index * 2, 3, "%02x", digest[index]);
    output[64] = '\0';
}

static void write_fence(const char *state, unsigned long long generation) {
    if (strcmp(state, "ENABLED") != 0 && strcmp(state, "DISABLED_PENDING_DB") != 0 &&
        strcmp(state, "DISABLED_COMMITTED") != 0) fatal("invalid fence state");
    char payload[256];
    int payload_length = snprintf(payload, sizeof(payload), "{\"generation\":%llu,\"state\":\"%s\",\"version\":1}", generation, state);
    if (payload_length <= 0 || (size_t)payload_length >= sizeof(payload)) fatal("cannot format fence");
    char hash[65];
    sha256_hex(payload, (size_t)payload_length, hash);

    char temporary[256], hash_temporary[256];
    snprintf(temporary, sizeof(temporary), FENCE_DIRECTORY "/.fence.tmp.%ld", (long)getpid());
    snprintf(hash_temporary, sizeof(hash_temporary), FENCE_DIRECTORY "/.fence-hash.tmp.%ld", (long)getpid());
    int fd = open(temporary, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) fatal("cannot create fence temp file");
    if (write(fd, payload, (size_t)payload_length) != payload_length || fsync(fd) != 0 || close(fd) != 0) {
        unlink(temporary);
        fatal("cannot persist fence temp file");
    }
    fd = open(hash_temporary, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0 || write(fd, hash, 64) != 64 || fsync(fd) != 0 || close(fd) != 0) {
        unlink(temporary); unlink(hash_temporary); fatal("cannot persist fence hash");
    }
    if (rename(temporary, FENCE_FILE) != 0) fatal("cannot replace fence record");
    if (rename(hash_temporary, FENCE_HASH_FILE) != 0) fatal("cannot replace fence hash");
    int directory = open(FENCE_DIRECTORY, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory < 0 || fsync(directory) != 0 || close(directory) != 0) fatal("cannot fsync fence directory");
    snprintf(fence_state, sizeof(fence_state), "%s", state);
}

static int read_fence(void) {
    int fd = open(FENCE_FILE, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    struct stat metadata;
    if (fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode) || metadata.st_uid != 0 || metadata.st_gid != 0 ||
        (metadata.st_mode & 0777) != 0600 || metadata.st_nlink != 1 || metadata.st_size > 320) { close(fd); return -1; }
    char record[320] = {0};
    ssize_t length = read(fd, record, sizeof(record));
    close(fd);
    if (length <= 0 || length >= (ssize_t)sizeof(record)) return -1;

    fd = open(FENCE_HASH_FILE, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    char stored_hash[65] = {0};
    if (fd < 0 || fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode) || metadata.st_uid != 0 || metadata.st_gid != 0 ||
        (metadata.st_mode & 0777) != 0600 || metadata.st_nlink != 1 || metadata.st_size != 64 ||
        read(fd, stored_hash, 65) != 64 || read(fd, stored_hash, 1) != 0) { if (fd >= 0) close(fd); return -1; }
    close(fd);
    char actual_hash[65]; sha256_hex(record, (size_t)length, actual_hash);
    if (strcmp(stored_hash, actual_hash) != 0) return -1;

    unsigned version = 0;
    unsigned long long generation = 0;
    char state[48] = {0};
    int consumed = 0;
    if (sscanf(record, "{\"generation\":%llu,\"state\":\"%47[A-Z_]\",\"version\":%u}%n",
        &generation, state, &version, &consumed) != 3 || version != 1 || consumed != length || generation > INT64_MAX) return -1;
    char payload[256];
    int payload_length = snprintf(payload, sizeof(payload), "{\"generation\":%llu,\"state\":\"%s\",\"version\":1}", generation, state);
    if (payload_length != length || memcmp(payload, record, (size_t)length) != 0) return -1;
    if (strcmp(state, "ENABLED") != 0 && strcmp(state, "DISABLED_PENDING_DB") != 0 && strcmp(state, "DISABLED_COMMITTED") != 0) return -1;
    fence_generation = generation;
    snprintf(fence_state, sizeof(fence_state), "%s", state);
    launches_enabled = strcmp(state, "ENABLED") == 0;
    return 0;
}

static int create_socket(const char *path, mode_t mode) {
    unlink(path);
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) fatal("cannot create socket");
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (strlen(path) >= sizeof(address.sun_path)) fatal("socket path is too long");
    strcpy(address.sun_path, path);
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0 ||
        (strcmp(path, FUKUROU_LAUNCH_SOCKET) == 0 && chown(path, 0, LLM_GID) != 0) ||
        chmod(path, mode) != 0 || listen(fd, 32) != 0) {
        fatal("cannot publish socket");
    }
    return fd;
}

static int receive_request(int fd, struct launch_request *request) {
    memset(request, 0, sizeof(*request));
    for (size_t index = 0; index < 16; index++) request->descriptors[index] = -1;
    struct iovec vector[2] = {
        {.iov_base = &request->header, .iov_len = sizeof(request->header)},
        {.iov_base = request->payload, .iov_len = sizeof(request->payload)},
    };
    char control[CMSG_SPACE(sizeof(int) * 16)] = {0};
    struct msghdr message = {
        .msg_iov = vector, .msg_iovlen = 2, .msg_control = control, .msg_controllen = sizeof(control),
    };
    ssize_t received = recvmsg(fd, &message, MSG_CMSG_CLOEXEC);
    int ancillary_valid = 1, rights_messages = 0;
    for (struct cmsghdr *header = CMSG_FIRSTHDR(&message); header != NULL; header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS || header->cmsg_len < CMSG_LEN(0)) {
            ancillary_valid = 0; continue;
        }
        rights_messages++;
        size_t count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        int *received_descriptors = (int *)CMSG_DATA(header);
        for (size_t index = 0; index < count; index++) {
            if (request->descriptor_count < 16) request->descriptors[request->descriptor_count++] = received_descriptors[index];
            else close(received_descriptors[index]);
        }
    }
    if (received < (ssize_t)sizeof(request->header) || (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) ||
        !ancillary_valid || rights_messages != (request->descriptor_count == 0 ? 0 : 1)) return -1;
    if (request->header.magic != FUKUROU_PROTOCOL_MAGIC || request->header.version != FUKUROU_PROTOCOL_VERSION ||
        request->header.header_size != sizeof(request->header) || request->header.total_length > sizeof(request->header) + sizeof(request->payload) ||
        received != (ssize_t)request->header.total_length ||
        request->header.argc == 0 || request->header.argc > FUKUROU_PROTOCOL_MAX_ITEMS ||
        request->header.envc > FUKUROU_PROTOCOL_MAX_ITEMS || request->descriptor_count > 6 ||
        __builtin_popcount(request->header.fd_role_bitmap) != request->descriptor_count) return -1;
    return 0;
}

static int split_payload(struct launch_request *request, char **arguments, char **environment) {
    size_t offset = 0;
    size_t payload_size = request->header.total_length - sizeof(request->header);
    for (size_t index = 0; index < (size_t)request->header.argc + request->header.envc; index++) {
        if (offset + sizeof(uint16_t) > payload_size) return -1;
        uint16_t length = 0; memcpy(&length, request->payload + offset, sizeof(length)); offset += sizeof(length);
        if (offset + length + 1 > payload_size) return -1;
        char *item = request->payload + offset;
        if (memchr(item, '\0', length) != NULL || item[length] != '\0') return -1;
        if (index < request->header.argc) arguments[index] = item;
        else environment[index - request->header.argc] = item;
        offset += length + 1;
    }
    if (offset != payload_size) return -1;
    arguments[request->header.argc] = NULL;
    environment[request->header.envc] = NULL;
    return 0;
}

static const char *executable_for(uint16_t profile, uid_t peer_uid, gid_t peer_gid) {
    if (profile == FUKUROU_PROFILE_CLAUDE_CURRENT_V1 && peer_uid == LLM_UID && peer_gid == LLM_GID) return "/usr/local/bin/claude";
    if (profile == FUKUROU_PROFILE_CODEX_CURRENT_V1 && peer_uid == LLM_UID && peer_gid == LLM_GID) return "/usr/local/bin/codex";
    if (profile == FUKUROU_PROFILE_FOUNDATION_CANARY_V1 && peer_uid == LLM_UID && peer_gid == LLM_GID) return "/usr/bin/node";
    if (profile == FUKUROU_PROFILE_MCP_CURRENT_V1 && peer_uid == MCP_UID && peer_gid == MCP_GID) return "/opt/java/openjdk/bin/java";
    return NULL;
}

static int environment_name_allowed(uint16_t profile, const char *entry) {
    static const char *llm_names[] = {
        "PATH=", "HOME=", "CODEX_HOME=", "CLAUDE_CONFIG_DIR=", "LANG=", "LC_ALL=", "TERM=", "TMPDIR=",
        "XDG_CACHE_HOME=", "FUKUROU_INVOCATION_ID=", "FUKUROU_LLM_PROVIDER=", "FUKUROU_PROMPT_HASH=",
        "FUKUROU_SYSTEM_PROMPT_VERSION=", "FUKUROU_MARKET_SNAPSHOT_ID=", "FUKUROU_RUNTIME_CONFIG_VERSION_ID=",
        "FUKUROU_RUNTIME_CONFIG_HASH=", "FUKUROU_FALSIFIER_INTENT_ID=", "FUKUROU_CANARY_INTENT_ID=",
        "FUKUROU_CANARY_LLM_DUMPABLE=", "FUKUROU_CANARY_LLM_CORE_LIMIT=", "FUKUROU_CANARY_LLM_LAUNCH_FDS=",
    };
    if (profile == FUKUROU_PROFILE_MCP_CURRENT_V1) {
        return strcmp(entry, "PATH=/opt/java/openjdk/bin:/usr/bin:/bin") == 0 ||
            strncmp(entry, "FUKUROU_INVOCATION_ID=", 22) == 0;
    }
    if (strncmp(entry, "HOME=/root", 10) == 0 || strncmp(entry, "CODEX_HOME=/root", 16) == 0 ||
        strncmp(entry, "CLAUDE_CONFIG_DIR=/root", 24) == 0 || strncmp(entry, "TMPDIR=/root", 12) == 0 ||
        strncmp(entry, "XDG_CACHE_HOME=/root", 20) == 0) return 0;
    for (size_t index = 0; index < sizeof(llm_names) / sizeof(llm_names[0]); index++) {
        if (strncmp(entry, llm_names[index], strlen(llm_names[index])) == 0) return 1;
    }
    return 0;
}

static int bounded_text(const char *value, size_t maximum) {
    size_t length = strlen(value);
    if (length == 0 || length > maximum) return 0;
    for (size_t index = 0; index < length; index++) if ((unsigned char)value[index] < 0x20 && value[index] != '\n' && value[index] != '\t') return 0;
    return 1;
}

static int model_value_allowed(const char *value) {
    if (!bounded_text(value, 96)) return 0;
    for (const char *cursor = value; *cursor != '\0'; cursor++) {
        if (!(isalnum((unsigned char)*cursor) || *cursor == '-' || *cursor == '_' || *cursor == '.' || *cursor == '/')) return 0;
    }
    return 1;
}

static int canonical_identifier(const char *value, size_t maximum) {
    size_t length = strlen(value);
    if (length == 0 || length > maximum) return 0;
    for (size_t index = 0; index < length; index++) {
        unsigned char current = (unsigned char)value[index];
        if (!(isalnum(current) || current == '.' || current == '_' || current == ':' || current == '-')) return 0;
    }
    return 1;
}

static int lowercase_hex(const char *value, size_t expected_length) {
    if (value == NULL || strlen(value) != expected_length) return 0;
    for (const char *cursor = value; *cursor != '\0'; cursor++) {
        if (!((*cursor >= '0' && *cursor <= '9') || (*cursor >= 'a' && *cursor <= 'f'))) return 0;
    }
    return 1;
}

static int canonical_run_directory(const char *value, const char *prefix) {
    static const char *root = "/run/fukurou/llm-homes/";
    size_t root_length = strlen(root), prefix_length = strlen(prefix);
    if (strncmp(value, root, root_length) != 0 || strncmp(value + root_length, prefix, prefix_length) != 0) return 0;
    const char *suffix = value + root_length + prefix_length;
    if (*suffix == '\0' || strstr(suffix, "..") != NULL || strchr(suffix, '/') != NULL) return 0;
    for (const char *cursor = suffix; *cursor != '\0'; cursor++) {
        if (!(isalnum((unsigned char)*cursor) || *cursor == '-' || *cursor == '_')) return 0;
    }
    return strlen(value) <= 256;
}

static int canonical_claude_config_path(const char *path, const char *home) {
    if (path == NULL || home == NULL) return 0;
    size_t home_length = strlen(home);
    if (!canonical_run_directory(home, "fukurou-llm-config-") || strncmp(path, home, home_length) != 0 ||
        path[home_length] != '/' || strstr(path, "..") != NULL) return 0;
    const char *name = path + home_length + 1;
    static const char *prefix = "claude-mcp-config";
    size_t length = strlen(name), prefix_length = strlen(prefix);
    if (length <= prefix_length + 5 || strcmp(name + length - 5, ".json") != 0 ||
        strncmp(name, prefix, prefix_length) != 0) return 0;
    for (size_t index = prefix_length; index < length - 5; index++) {
        if (!(isalnum((unsigned char)name[index]) || name[index] == '-' || name[index] == '_')) return 0;
    }
    return 1;
}

static int exact_mcp_tool_allowlist(const char *value) {
    static const char *proposer = "mcp__fukurou-mcp__get_trade_intent,mcp__fukurou-mcp__get_ticker,mcp__fukurou-mcp__get_candles,mcp__fukurou-mcp__get_orderbook,mcp__fukurou-mcp__get_trades,mcp__fukurou-mcp__get_symbol_rules,mcp__fukurou-mcp__calc_indicator,mcp__fukurou-mcp__get_balance,mcp__fukurou-mcp__get_positions,mcp__fukurou-mcp__get_open_orders,mcp__fukurou-mcp__get_account_status,mcp__fukurou-mcp__knowledge_get_recent_lessons,mcp__fukurou-mcp__knowledge_search_similar_setups,mcp__fukurou-mcp__submit_decision";
    static const char *falsifier = "mcp__fukurou-mcp__get_trade_intent,mcp__fukurou-mcp__preview_order,mcp__fukurou-mcp__get_ticker,mcp__fukurou-mcp__get_candles,mcp__fukurou-mcp__get_orderbook,mcp__fukurou-mcp__get_trades,mcp__fukurou-mcp__get_symbol_rules,mcp__fukurou-mcp__calc_indicator,mcp__fukurou-mcp__get_balance,mcp__fukurou-mcp__get_positions,mcp__fukurou-mcp__get_open_orders,mcp__fukurou-mcp__get_account_status,mcp__fukurou-mcp__knowledge_get_recent_lessons,mcp__fukurou-mcp__knowledge_search_similar_setups,mcp__fukurou-mcp__submit_falsification";
    return *value == '\0' || strcmp(value, proposer) == 0 || strcmp(value, falsifier) == 0;
}

static int claude_arguments_allowed(struct launch_request *request, char **arguments, char **environment) {
    size_t index = 0;
    if (request->header.argc < 15 || strcmp(arguments[index++], "claude") != 0 || strcmp(arguments[index++], "-p") != 0 ||
        !bounded_text(arguments[index++], 32768)) return 0;
    if (index < request->header.argc && strcmp(arguments[index], "--model") == 0) {
        if (++index >= request->header.argc || !model_value_allowed(arguments[index++])) return 0;
    }
    if (index < request->header.argc && strcmp(arguments[index], "--effort") == 0) {
        if (++index >= request->header.argc || (strcmp(arguments[index], "low") != 0 && strcmp(arguments[index], "medium") != 0 &&
            strcmp(arguments[index], "high") != 0 && strcmp(arguments[index], "xhigh") != 0)) return 0;
        index++;
    }
    if (index < request->header.argc && strcmp(arguments[index], "--bare") == 0) index++;
    if (index + 12 != request->header.argc || strcmp(arguments[index++], "--mcp-config") != 0 ||
        !canonical_claude_config_path(arguments[index], environment_value(environment, "CLAUDE_CONFIG_DIR")) ||
        !bounded_text(arguments[index++], 256) ||
        strcmp(arguments[index++], "--strict-mcp-config") != 0 || strcmp(arguments[index++], "--allowedTools") != 0 ||
        !exact_mcp_tool_allowlist(arguments[index++]) || strcmp(arguments[index++], "--tools") != 0) return 0;
    if (strcmp(arguments[index], "ToolSearch") != 0 && strcmp(arguments[index], "") != 0) return 0;
    index++;
    if (strcmp(arguments[index++], "--permission-mode") != 0 || strcmp(arguments[index++], "dontAsk") != 0 ||
        strcmp(arguments[index++], "--output-format") != 0 || strcmp(arguments[index++], "json") != 0 ||
        strcmp(arguments[index++], "--no-session-persistence") != 0) return 0;
    return index == request->header.argc;
}

static int codex_arguments_allowed(struct launch_request *request, char **arguments) {
    size_t index = 0;
    if (request->header.argc < 9 || strcmp(arguments[index++], "codex") != 0 || strcmp(arguments[index++], "exec") != 0) return 0;
    if (index < request->header.argc && strcmp(arguments[index], "-m") == 0) {
        if (++index >= request->header.argc || !model_value_allowed(arguments[index++])) return 0;
    }
    if (index + 7 != request->header.argc || strcmp(arguments[index++], "--json") != 0 ||
        strcmp(arguments[index++], "--skip-git-repo-check") != 0 || strcmp(arguments[index++], "--sandbox") != 0 ||
        strcmp(arguments[index++], "read-only") != 0 || strcmp(arguments[index++], "-c") != 0 ||
        strcmp(arguments[index++], "approval_policy=\"never\"") != 0 || !bounded_text(arguments[index++], 32768)) return 0;
    return index == request->header.argc;
}

static int descriptors_allowed(struct launch_request *request) {
    for (size_t index = 0; index < request->descriptor_count; index++) {
        struct stat current;
        if (fstat(request->descriptors[index], &current) != 0) return 0;
        int seals = request->header.profile == FUKUROU_PROFILE_MCP_CURRENT_V1 && (index == 3 || index == 4)
            ? fcntl(request->descriptors[index], F_GET_SEALS) : -1;
        int standard_descriptor = index < 3 &&
            (S_ISREG(current.st_mode) || S_ISCHR(current.st_mode) || S_ISFIFO(current.st_mode) || S_ISSOCK(current.st_mode));
        int mcp_sealed_input = request->header.profile == FUKUROU_PROFILE_MCP_CURRENT_V1 &&
            (index == 3 || index == 4) && S_ISREG(current.st_mode) && seals >= 0 &&
            (seals & (F_SEAL_SEAL | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE)) ==
                (F_SEAL_SEAL | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE);
        struct sockaddr_storage socket_address = {0};
        socklen_t socket_address_length = sizeof(socket_address);
        int mcp_submission_socket = request->header.profile == FUKUROU_PROFILE_MCP_CURRENT_V1 &&
            index == 5 && S_ISSOCK(current.st_mode) &&
            getsockname(request->descriptors[index], (struct sockaddr *)&socket_address, &socket_address_length) == 0 &&
            socket_address.ss_family == AF_UNIX;
        int type_allowed = standard_descriptor || mcp_sealed_input || mcp_submission_socket;
        if (!type_allowed) return 0;
        for (size_t previous = 0; previous < index; previous++) {
            struct stat other;
            if (fstat(request->descriptors[previous], &other) != 0 ||
                (current.st_dev == other.st_dev && current.st_ino == other.st_ino)) return 0;
        }
    }
    return 1;
}

static int environment_entries_wellformed(uint16_t profile, char **environment, uint16_t environment_count) {
    for (size_t index = 0; index < environment_count; index++) {
        if (!environment_name_allowed(profile, environment[index])) return 0;
        const char *separator = strchr(environment[index], '=');
        if (separator == NULL || separator == environment[index] || !bounded_text(separator + 1, 1024)) return 0;
        size_t name_length = (size_t)(separator - environment[index]);
        for (size_t previous = 0; previous < index; previous++) {
            if (strncmp(environment[previous], environment[index], name_length) == 0 &&
                environment[previous][name_length] == '=') return 0;
        }
    }
    return 1;
}

/* CLI version probe は固定 argv・読み取り専用で、run 用 env 契約を課さない。 */
static int version_probe_request(struct launch_request *request, char **arguments) {
    if (request->header.profile != FUKUROU_PROFILE_CLAUDE_CURRENT_V1 &&
        request->header.profile != FUKUROU_PROFILE_CODEX_CURRENT_V1) return 0;
    if (request->header.fd_role_bitmap != 0x7U || request->header.argc != 2) return 0;
    const char *name = request->header.profile == FUKUROU_PROFILE_CLAUDE_CURRENT_V1 ? "claude" : "codex";
    return strcmp(arguments[0], name) == 0 && strcmp(arguments[1], "--version") == 0;
}

static int environment_entries_allowed(uint16_t profile, char **environment, uint16_t environment_count) {
    if (!environment_entries_wellformed(profile, environment, environment_count)) return 0;
    const char *invocation_id = environment_value(environment, "FUKUROU_INVOCATION_ID");
    if (!canonical_identifier(invocation_id == NULL ? "" : invocation_id, 128)) return 0;
    if (profile == FUKUROU_PROFILE_MCP_CURRENT_V1) {
        return environment_count == 2 &&
            strcmp(environment[0], "PATH=/opt/java/openjdk/bin:/usr/bin:/bin") == 0 &&
            strncmp(environment[1], "FUKUROU_INVOCATION_ID=", 22) == 0;
    }
    const char *path = environment_value(environment, "PATH");
    const char *lang = environment_value(environment, "LANG");
    const char *lc_all = environment_value(environment, "LC_ALL");
    if (path == NULL || strcmp(path, "/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") != 0 ||
        lang == NULL || strcmp(lang, "en_US.UTF-8") != 0 || lc_all == NULL || strcmp(lc_all, "en_US.UTF-8") != 0) return 0;
    if (profile == FUKUROU_PROFILE_CLAUDE_CURRENT_V1 || profile == FUKUROU_PROFILE_CODEX_CURRENT_V1) {
        const char *prompt_hash = environment_value(environment, "FUKUROU_PROMPT_HASH");
        const char *runtime_hash = environment_value(environment, "FUKUROU_RUNTIME_CONFIG_HASH");
        if (!lowercase_hex(prompt_hash, 64) || (runtime_hash != NULL && !lowercase_hex(runtime_hash, 64))) return 0;
    }
    if (profile == FUKUROU_PROFILE_CLAUDE_CURRENT_V1) {
        const char *home = environment_value(environment, "CLAUDE_CONFIG_DIR");
        const char *declared_home = environment_value(environment, "HOME");
        const char *provider = environment_value(environment, "FUKUROU_LLM_PROVIDER");
        const char *cache = environment_value(environment, "XDG_CACHE_HOME");
        if (home == NULL || !canonical_run_directory(home, "fukurou-llm-config-") ||
            declared_home == NULL || strcmp(declared_home, home) != 0 ||
            cache == NULL || strncmp(cache, home, strlen(home)) != 0 || strcmp(cache + strlen(home), "/.cache") != 0 ||
            provider == NULL || strcmp(provider, "claude") != 0) return 0;
    }
    if (profile == FUKUROU_PROFILE_CODEX_CURRENT_V1) {
        const char *home = environment_value(environment, "CODEX_HOME");
        const char *declared_home = environment_value(environment, "HOME");
        const char *provider = environment_value(environment, "FUKUROU_LLM_PROVIDER");
        const char *cache = environment_value(environment, "XDG_CACHE_HOME");
        if (home == NULL || !canonical_run_directory(home, "fukurou-codex-home-") ||
            declared_home == NULL || strcmp(declared_home, home) != 0 ||
            cache == NULL || strncmp(cache, home, strlen(home)) != 0 || strcmp(cache + strlen(home), "/.cache") != 0 ||
            provider == NULL || strcmp(provider, "codex") != 0) return 0;
    }
    return 1;
}

static int canary_environment_allowed(char **arguments, char **environment) {
    const char *home = environment_value(environment, "HOME");
    const char *profile_home = strcmp(arguments[3], "PROPOSER") == 0
        ? environment_value(environment, "CLAUDE_CONFIG_DIR") : environment_value(environment, "CODEX_HOME");
    const char *provider = environment_value(environment, "FUKUROU_LLM_PROVIDER");
    const char *cache = environment_value(environment, "XDG_CACHE_HOME");
    char expected_cache[320];
    if (home == NULL || profile_home == NULL || provider == NULL || cache == NULL || strcmp(home, profile_home) != 0 ||
        !canonical_run_directory(home, strcmp(arguments[3], "PROPOSER") == 0 ? "fukurou-llm-config-" : "fukurou-codex-home-")) return 0;
    snprintf(expected_cache, sizeof(expected_cache), "%s/.cache", home);
    if (strcmp(cache, expected_cache) != 0) return 0;
    if (strcmp(provider, strcmp(arguments[3], "PROPOSER") == 0 ? "claude" : "codex") != 0) return 0;
    const char *prompt_hash = environment_value(environment, "FUKUROU_PROMPT_HASH");
    const char *prompt_version = environment_value(environment, "FUKUROU_SYSTEM_PROMPT_VERSION");
    const char *snapshot = environment_value(environment, "FUKUROU_MARKET_SNAPSHOT_ID");
    const char *config_version = environment_value(environment, "FUKUROU_RUNTIME_CONFIG_VERSION_ID");
    const char *config_hash = environment_value(environment, "FUKUROU_RUNTIME_CONFIG_HASH");
    return prompt_hash != NULL && strcmp(prompt_hash, "canary-prompt-hash") == 0 &&
        prompt_version != NULL && strcmp(prompt_version, "canary-system-prompt") == 0 &&
        snapshot != NULL && strcmp(snapshot, "canary-snapshot") == 0 &&
        config_version != NULL && strcmp(config_version, "canary-config") == 0 &&
        config_hash != NULL && strcmp(config_hash, "canary-config-hash") == 0;
}

static int rro_action_allowed(const char *action) {
    return strcmp(action, "NO_TRADE") == 0 || strcmp(action, "EXIT") == 0 ||
        strcmp(action, "REDUCE") == 0 || strcmp(action, "ADJUST_PROTECTION") == 0 ||
        strcmp(action, "ENTER") == 0 || strcmp(action, "ADD_LONG") == 0;
}

static int canary_arguments_allowed(struct launch_request *request, char **arguments) {
    if ((request->header.argc != 4 && request->header.argc != 5) ||
        strcmp(arguments[0], "node") != 0 ||
        strcmp(arguments[1], "/usr/local/libexec/fukurou-mcp-canary-client.mjs") != 0 ||
        !lowercase_hex(arguments[2], 48)) return 0;
    if (request->header.argc == 4) {
        return strcmp(arguments[3], "PROPOSER") == 0 || strcmp(arguments[3], "FALSIFIER") == 0;
    }
    return request->header.argc == 5 && strcmp(arguments[3], "RISK_REDUCTION_ONLY") == 0 &&
        rro_action_allowed(arguments[4]);
}

static int request_shape_allowed(struct launch_request *request, char **arguments, char **environment) {
    if (version_probe_request(request, arguments)) {
        if (!environment_entries_wellformed(request->header.profile, environment, request->header.envc)) return 0;
        return descriptors_allowed(request);
    }
    if ((request->header.profile == FUKUROU_PROFILE_CLAUDE_CURRENT_V1 || request->header.profile == FUKUROU_PROFILE_CODEX_CURRENT_V1) &&
        request->header.fd_role_bitmap == 0x7U &&
        strcmp(arguments[0], request->header.profile == FUKUROU_PROFILE_CLAUDE_CURRENT_V1 ? "claude" : "codex") == 0) {
        if (request->header.profile == FUKUROU_PROFILE_CLAUDE_CURRENT_V1 ? !claude_arguments_allowed(request, arguments, environment) : !codex_arguments_allowed(request, arguments)) return 0;
    } else if (request->header.profile == FUKUROU_PROFILE_FOUNDATION_CANARY_V1 && request->header.fd_role_bitmap == 0x7U &&
        canary_arguments_allowed(request, arguments) &&
        canary_environment_allowed(arguments, environment)) {
        /* Exact signed fixture profile is accepted. */
    } else if (request->header.profile == FUKUROU_PROFILE_MCP_CURRENT_V1 && request->header.fd_role_bitmap == 0x3fU &&
        request->header.argc == 5 && strcmp(arguments[0], "java") == 0 &&
        strcmp(arguments[1], "--add-opens=java.base/java.io=ALL-UNNAMED") == 0 &&
        strcmp(arguments[2], "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED") == 0 &&
        strcmp(arguments[3], "-jar") == 0 && strcmp(arguments[4], "/app/fukurou-mcp-all.jar") == 0) {
        /* MCP has a fixed argv contract. */
    } else {
        return 0;
    }
    if (!environment_entries_allowed(request->header.profile, environment, request->header.envc)) return 0;
    return descriptors_allowed(request);
}

static void close_request_descriptors(struct launch_request *request) {
    for (size_t index = 0; index < request->descriptor_count; index++) {
        if (request->descriptors[index] >= 0) close(request->descriptors[index]);
    }
}

static int consume_request_nonce(const unsigned char nonce[FUKUROU_PROTOCOL_NONCE_SIZE]) {
    unsigned char combined = 0;
    for (size_t byte = 0; byte < FUKUROU_PROTOCOL_NONCE_SIZE; byte++) combined |= nonce[byte];
    if (combined == 0) return 0;
    for (size_t index = 0; index < 128; index++) {
        if (memcmp(recent_nonces[index], nonce, FUKUROU_PROTOCOL_NONCE_SIZE) == 0) return 0;
    }
    memcpy(recent_nonces[recent_nonce_cursor], nonce, FUKUROU_PROTOCOL_NONCE_SIZE);
    recent_nonce_cursor = (recent_nonce_cursor + 1) % 128;
    return 1;
}

static size_t available_job_slot(void) {
    size_t slot = 0;
    while (slot < MAX_JOBS && jobs[slot].pid != 0) slot++;
    return slot;
}

static void reject_spawned_child(pid_t child, int start_gate, struct launch_request *request) {
    close(start_gate);
    kill(child, SIGKILL);
    waitpid(child, NULL, 0);
    close_request_descriptors(request);
}

static void accept_launch(int listener) {
    int connection = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
    if (connection < 0) return;
    struct ucred credentials;
    socklen_t credential_length = sizeof(credentials);
    if (getsockopt(connection, SOL_SOCKET, SO_PEERCRED, &credentials, &credential_length) != 0) {
        close(connection);
        return;
    }
    struct launch_request request;
    if (receive_request(connection, &request) != 0) {
        close_request_descriptors(&request);
        close(connection);
        return;
    }
    if (!launches_enabled || !consume_request_nonce(request.header.request_nonce)) {
        int status = 125; send(connection, &status, sizeof(status), MSG_NOSIGNAL);
        close_request_descriptors(&request); close(connection); return;
    }
    const char *executable = executable_for(request.header.profile, credentials.uid, credentials.gid);
    char *arguments[FUKUROU_PROTOCOL_MAX_ITEMS + 1], *environment[FUKUROU_PROTOCOL_MAX_ITEMS + 1];
    if (executable == NULL || split_payload(&request, arguments, environment) != 0 ||
        !request_shape_allowed(&request, arguments, environment)) {
        close_request_descriptors(&request);
        close(connection);
        return;
    }
    size_t slot = available_job_slot();
    if (slot == MAX_JOBS) {
        close_request_descriptors(&request);
        close(connection);
        return;
    }
    int start_gate[2];
    if (pipe2(start_gate, O_CLOEXEC) != 0) {
        close_request_descriptors(&request);
        close(connection);
        return;
    }
    pid_t child = fork();
    if (child < 0) {
        close(start_gate[0]); close(start_gate[1]);
        close_request_descriptors(&request);
        close(connection);
        return;
    }
    if (child == 0) {
        close(start_gate[1]);
        char permission = 0;
        if (read(start_gate[0], &permission, 1) != 1 || permission != '1') _exit(126);
        close(start_gate[0]);
        for (size_t index = 0; index < request.descriptor_count; index++) {
            if (dup2(request.descriptors[index], (int)index) < 0) _exit(126);
        }
        if (setgroups(0, NULL) != 0 || setresgid(credentials.gid, credentials.gid, credentials.gid) != 0 ||
            setresuid(credentials.uid, credentials.uid, credentials.uid) != 0) _exit(126);
        umask(0007);
        close_range(request.descriptor_count, ~0U, 0);
        execve(executable, arguments, environment);
        _exit(126);
    }
    close(start_gate[0]);
    const char *invocation_id = environment_value(environment, "FUKUROU_INVOCATION_ID");
    const char *registration_role = request.header.profile == FUKUROU_PROFILE_MCP_CURRENT_V1 ? "MCP" : "PROVIDER";
    int receipt_result = request.header.profile == FUKUROU_PROFILE_FOUNDATION_CANARY_V1 ||
            version_probe_request(&request, arguments)
        ? 0 : register_spawn_receipt(registration_role, invocation_id, child);
    if (receipt_result != 0 || write(start_gate[1], "1", 1) != 1) {
        reject_spawned_child(child, start_gate[1], &request);
        int status = 125;
        send(connection, &status, sizeof(status), MSG_NOSIGNAL);
        close(connection);
        return;
    }
    close(start_gate[1]);
    close_request_descriptors(&request);
    jobs[slot] = (struct job){.pid = child, .response_fd = connection};
}

static void reap_jobs(void) {
    for (size_t index = 0; index < MAX_JOBS; index++) {
        if (jobs[index].pid == 0) continue;
        int wait_status = 0;
        pid_t result = waitpid(jobs[index].pid, &wait_status, WNOHANG);
        if (result <= 0) continue;
        int status = WIFEXITED(wait_status) ? WEXITSTATUS(wait_status) : 128 + WTERMSIG(wait_status);
        send(jobs[index].response_fd, &status, sizeof(status), MSG_NOSIGNAL);
        close(jobs[index].response_fd);
        jobs[index] = (struct job){0};
    }
}

static void accept_control(int listener) {
    int connection = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
    if (connection < 0) return;
    struct ucred credentials;
    socklen_t length = sizeof(credentials);
    char command[96] = {0};
    if (getsockopt(connection, SOL_SOCKET, SO_PEERCRED, &credentials, &length) != 0 || credentials.uid != 0 ||
        recv(connection, command, sizeof(command) - 1, 0) <= 0) {
        close(connection);
        return;
    }
    char operation[16] = {0};
    unsigned long long generation = 0;
    if (sscanf(command, "%15s %llu", operation, &generation) != 2 || generation < fence_generation) {
        close(connection);
        return;
    }
    if (strcmp(operation, "DISABLE") == 0) {
        launches_enabled = 0;
        if (launch_listener >= 0) { close(launch_listener); launch_listener = -1; unlink(FUKUROU_LAUNCH_SOCKET); }
        fence_generation = generation;
        write_fence("DISABLED_PENDING_DB", generation);
    } else if (strcmp(operation, "COMMIT") == 0 && generation == fence_generation) {
        unsigned long long database_generation = 0;
        int maintenance_enabled = 0;
        unsigned active_registrations = 0;
        if (read_database_state(&database_generation, &maintenance_enabled, &active_registrations) != 0 || active_registrations != 0 ||
            database_generation != generation || !maintenance_enabled) {
            close(connection);
            return;
        }
        write_fence("DISABLED_COMMITTED", generation);
    } else if (strcmp(operation, "ENABLE") == 0) {
        unsigned long long database_generation = 0;
        int maintenance_enabled = 0;
        unsigned active_registrations = 0;
        if (read_database_state(&database_generation, &maintenance_enabled, &active_registrations) != 0 || active_registrations != 0 ||
            database_generation != generation || maintenance_enabled) {
            close(connection);
            return;
        }
        fence_generation = generation;
        write_fence("ENABLED", generation);
        launches_enabled = 1;
        if (launch_listener < 0) launch_listener = create_socket(FUKUROU_LAUNCH_SOCKET, 0666);
    } else {
        close(connection);
        return;
    }
    dprintf(connection, "ACK %s %llu\n", operation, generation);
    close(connection);
}

static pid_t start_application(void) {
    pid_t child = fork();
    if (child != 0) return child;
    if (setgroups(0, NULL) != 0 || setresgid(APP_GID, APP_GID, APP_GID) != 0 || setresuid(APP_UID, APP_UID, APP_UID) != 0) {
        _exit(126);
    }
    char *const arguments[] = {"java", "-jar", "/app/app.jar", NULL};
    extern char **environ;
    execve("/opt/java/openjdk/bin/java", arguments, environ);
    _exit(126);
}

static int run_canary_preflight(char **arguments) {
    pid_t child = fork();
    if (child < 0) return 125;
    if (child == 0) {
        if (setgroups(0, NULL) != 0 || setresgid(APP_GID, APP_GID, APP_GID) != 0 ||
            setresuid(APP_UID, APP_UID, APP_UID) != 0) _exit(126);
        char *const java_arguments[] = {
            "java", "-cp", "/app/app.jar", "me.matsumo.fukurou.DeploymentPreflightMain",
            arguments[0], arguments[1], arguments[2], arguments[3], NULL,
        };
        extern char **environ;
        execve("/opt/java/openjdk/bin/java", java_arguments, environ);
        _exit(126);
    }
    int status = 0;
    if (waitpid(child, &status, 0) != child) return 125;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

static int read_database_state(unsigned long long *generation, int *maintenance_enabled, unsigned *active_registrations) {
    int output[2];
    if (pipe2(output, O_CLOEXEC) != 0) return -1;
    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        if (dup2(output[1], STDOUT_FILENO) < 0 || setgroups(0, NULL) != 0 ||
            setresgid(APP_GID, APP_GID, APP_GID) != 0 || setresuid(APP_UID, APP_UID, APP_UID) != 0) _exit(126);
        close_range(3, ~0U, 0);
        char *const arguments[] = {
            "java", "-cp", "/app/app.jar", "me.matsumo.fukurou.LaunchFenceDatabaseProbeMain", NULL,
        };
        extern char **environ;
        execve("/opt/java/openjdk/bin/java", arguments, environ);
        _exit(126);
    }
    close(output[1]);
    char response[96] = {0};
    ssize_t length = read(output[0], response, sizeof(response) - 1);
    close(output[0]);
    int status = 0;
    if (waitpid(child, &status, 0) != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0 || length <= 0) return -1;
    unsigned long long database_generation = 0;
    char maintenance[8] = {0};
    unsigned registrations = 0;
    if (sscanf(response, "%llu|%7[^|]|%u", &database_generation, maintenance, &registrations) != 3) return -1;
    *generation = database_generation;
    *maintenance_enabled = strcmp(maintenance, "true") == 0;
    *active_registrations = registrations;
    return 0;
}

static int reconcile_database(void) {
    unsigned long long database_generation = 0;
    int database_disabled = 0;
    unsigned active_registrations = 0;
    if (read_database_state(&database_generation, &database_disabled, &active_registrations) != 0 ||
        database_generation != fence_generation || active_registrations != 0) {
        return -1;
    }
    int fence_disabled = strcmp(fence_state, "DISABLED_PENDING_DB") == 0 ||
        strcmp(fence_state, "DISABLED_COMMITTED") == 0;
    if (database_disabled != fence_disabled) return -1;
    launches_enabled = !database_disabled && strcmp(fence_state, "ENABLED") == 0;
    if (database_disabled && strcmp(fence_state, "DISABLED_PENDING_DB") == 0) {
        write_fence("DISABLED_COMMITTED", fence_generation);
    }
    return 0;
}

static int control_client(const char *operation, const char *generation) {
    if (strcmp(operation, "DISABLE") != 0 && strcmp(operation, "COMMIT") != 0 && strcmp(operation, "ENABLE") != 0) {
        return 2;
    }
    if (*generation == '\0') return 2;
    for (const char *cursor = generation; *cursor != '\0'; cursor++) if (*cursor < '0' || *cursor > '9') return 2;
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return 125;
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strcpy(address.sun_path, CONTROL_SOCKET);
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) != 0) return 125;
    char request[96];
    int request_length = snprintf(request, sizeof(request), "%s %s", operation, generation);
    if (send(fd, request, (size_t)request_length, MSG_NOSIGNAL) != request_length) return 125;
    char response[96] = {0};
    ssize_t response_length = recv(fd, response, sizeof(response) - 1, 0);
    close(fd);
    if (response_length <= 0) return 125;
    printf("%s", response);
    return 0;
}

static int count_open_descriptors(void) {
    DIR *directory = opendir("/proc/self/fd");
    if (directory == NULL) return -1;
    int count = -1;
    while (readdir(directory) != NULL) count++;
    closedir(directory);
    return count;
}

static int protocol_reject_case(unsigned case_id) {
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) != 0) return -1;
    struct fukurou_launch_header header = {
        .magic = FUKUROU_PROTOCOL_MAGIC,
        .version = FUKUROU_PROTOCOL_VERSION,
        .header_size = sizeof(struct fukurou_launch_header),
        .total_length = sizeof(struct fukurou_launch_header),
        .profile = FUKUROU_PROFILE_MCP_CURRENT_V1,
        .argc = 1,
    };
    uint16_t descriptor_count = 0;
    switch (case_id) {
        case 0: header.magic = 0; break;
        case 1: header.version++; break;
        case 2: header.header_size--; break;
        case 3: header.total_length--; break;
        case 4: header.argc = 0; break;
        case 5: header.argc = FUKUROU_PROTOCOL_MAX_ITEMS + 1; break;
        case 6: header.envc = FUKUROU_PROTOCOL_MAX_ITEMS + 1; break;
        case 7: header.fd_role_bitmap = FUKUROU_FD_STDIN; break;
        case 8: descriptor_count = 17; header.fd_role_bitmap = 0x1fU; break;
        default: close(pair[0]); close(pair[1]); return -1;
    }
    int descriptors[17];
    char control[CMSG_SPACE(sizeof(descriptors))];
    memset(control, 0, sizeof(control));
    struct iovec vector = {.iov_base = &header, .iov_len = sizeof(header)};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1};
    if (descriptor_count > 0) {
        for (size_t index = 0; index < descriptor_count; index++) {
            descriptors[index] = open("/dev/null", O_RDONLY | O_CLOEXEC);
            if (descriptors[index] < 0) return -1;
        }
        message.msg_control = control;
        message.msg_controllen = CMSG_SPACE(sizeof(int) * descriptor_count);
        struct cmsghdr *control_header = CMSG_FIRSTHDR(&message);
        control_header->cmsg_level = SOL_SOCKET;
        control_header->cmsg_type = SCM_RIGHTS;
        control_header->cmsg_len = CMSG_LEN(sizeof(int) * descriptor_count);
        memcpy(CMSG_DATA(control_header), descriptors, sizeof(int) * descriptor_count);
    }
    ssize_t sent = sendmsg(pair[0], &message, MSG_NOSIGNAL);
    for (size_t index = 0; index < descriptor_count; index++) close(descriptors[index]);
    close(pair[0]);
    if (sent != (ssize_t)sizeof(header)) { close(pair[1]); return -1; }
    struct launch_request request;
    int rejected = receive_request(pair[1], &request) != 0;
    close_request_descriptors(&request);
    close(pair[1]);
    return rejected ? 0 : -1;
}

enum accept_selftest_case {
    ACCEPT_BASELINE,
    ACCEPT_PATH_TRAVERSAL,
    ACCEPT_ROOT_HOME,
    ACCEPT_BAD_PATH,
    ACCEPT_BAD_MANIFEST,
    ACCEPT_BAD_PHASE,
    ACCEPT_BAD_PROVIDER,
    ACCEPT_BAD_HASH,
    ACCEPT_UNKNOWN_OPTION,
    ACCEPT_ARGUMENT_PERMUTATION,
    ACCEPT_DUPLICATE_ARGUMENT,
    ACCEPT_TRAILING_PAYLOAD,
    ACCEPT_UNKNOWN_ANCILLARY,
    ACCEPT_NONCE_REPLAY,
    ACCEPT_JOB_FULL,
    ACCEPT_RECEIPT_FAILURE,
    ACCEPT_VERSION_PROBE,
};

static int append_launch_item(char *payload, size_t *offset, const char *value) {
    size_t length = strlen(value);
    if (length > UINT16_MAX || *offset + sizeof(uint16_t) + length + 1 > FUKUROU_PROTOCOL_MAX_PAYLOAD) return -1;
    uint16_t encoded_length = (uint16_t)length;
    memcpy(payload + *offset, &encoded_length, sizeof(encoded_length));
    *offset += sizeof(encoded_length);
    memcpy(payload + *offset, value, length + 1);
    *offset += length + 1;
    return 0;
}

static int create_sealed_selftest_descriptor(void) {
    int descriptor = memfd_create("fukurou-protocol-selftest", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (descriptor < 0 || write(descriptor, "x", 1) != 1 || lseek(descriptor, 0, SEEK_SET) < 0 ||
        fcntl(descriptor, F_ADD_SEALS, F_SEAL_SEAL | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE) != 0) {
        if (descriptor >= 0) close(descriptor);
        return -1;
    }
    return descriptor;
}

static int send_accept_selftest_request(const char *path, enum accept_selftest_case test_case, unsigned nonce_seed) {
    uid_t uid = test_case == ACCEPT_RECEIPT_FAILURE ? MCP_UID : LLM_UID;
    gid_t gid = test_case == ACCEPT_RECEIPT_FAILURE ? MCP_GID : LLM_GID;
    if (setgroups(0, NULL) != 0 || setresgid(gid, gid, gid) != 0 || setresuid(uid, uid, uid) != 0) return 125;
    int connection = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    snprintf(address.sun_path, sizeof(address.sun_path), "%s", path);
    if (connection < 0 || connect(connection, (struct sockaddr *)&address, sizeof(address)) != 0) return 125;

    const char *canary_arguments[] = {
        "node", "/usr/local/libexec/fukurou-mcp-canary-client.mjs",
        "0123456789abcdef0123456789abcdef0123456789abcdef", "PROPOSER",
    };
    const char *mcp_arguments[] = {
        "java", "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "-jar", "/app/fukurou-mcp-all.jar",
    };
    const char *canary_environment[] = {
        "FUKUROU_INVOCATION_ID=accept-fixture",
        "PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG=en_US.UTF-8", "LC_ALL=en_US.UTF-8",
        "HOME=/run/fukurou/llm-homes/fukurou-llm-config-123",
        "CLAUDE_CONFIG_DIR=/run/fukurou/llm-homes/fukurou-llm-config-123",
        "XDG_CACHE_HOME=/run/fukurou/llm-homes/fukurou-llm-config-123/.cache",
        "FUKUROU_LLM_PROVIDER=claude", "FUKUROU_PROMPT_HASH=canary-prompt-hash",
        "FUKUROU_SYSTEM_PROMPT_VERSION=canary-system-prompt", "FUKUROU_MARKET_SNAPSHOT_ID=canary-snapshot",
        "FUKUROU_RUNTIME_CONFIG_VERSION_ID=canary-config", "FUKUROU_RUNTIME_CONFIG_HASH=canary-config-hash",
    };
    const char *root_environment[] = {
        "FUKUROU_INVOCATION_ID=accept-fixture", "HOME=/root",
        "PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG=en_US.UTF-8", "LC_ALL=en_US.UTF-8",
        "CLAUDE_CONFIG_DIR=/run/fukurou/llm-homes/fukurou-llm-config-123",
        "XDG_CACHE_HOME=/run/fukurou/llm-homes/fukurou-llm-config-123/.cache",
        "FUKUROU_LLM_PROVIDER=claude", "FUKUROU_PROMPT_HASH=canary-prompt-hash",
        "FUKUROU_SYSTEM_PROMPT_VERSION=canary-system-prompt", "FUKUROU_MARKET_SNAPSHOT_ID=canary-snapshot",
        "FUKUROU_RUNTIME_CONFIG_VERSION_ID=canary-config", "FUKUROU_RUNTIME_CONFIG_HASH=canary-config-hash",
    };
    const char *mcp_environment[] = {
        "PATH=/opt/java/openjdk/bin:/usr/bin:/bin", "FUKUROU_INVOCATION_ID=receipt-failure-fixture",
    };
    const char *version_arguments[] = {"codex", "--version"};
    const char *version_environment[] = {
        "PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME=/tmp/fukurou-cli-home",
    };
    const char **arguments = test_case == ACCEPT_RECEIPT_FAILURE ? mcp_arguments : canary_arguments;
    const char **environment = test_case == ACCEPT_RECEIPT_FAILURE ? mcp_environment : canary_environment;
    uint16_t argument_count = test_case == ACCEPT_RECEIPT_FAILURE ? 5 : 4;
    uint16_t environment_count = test_case == ACCEPT_RECEIPT_FAILURE ? 2 : 13;
    if (test_case == ACCEPT_VERSION_PROBE) {
        arguments = version_arguments;
        environment = version_environment;
        argument_count = 2;
        environment_count = 2;
    }
    const char *mutated_arguments[5];
    memcpy(mutated_arguments, canary_arguments, sizeof(canary_arguments));
    const char *mutated_environment[13];
    memcpy(mutated_environment, canary_environment, sizeof(canary_environment));
    if (test_case == ACCEPT_PATH_TRAVERSAL) mutated_arguments[1] = "/usr/local/libexec/../fukurou-mcp-canary-client.mjs";
    if (test_case == ACCEPT_BAD_MANIFEST) mutated_arguments[2] = "not-a-manifest";
    if (test_case == ACCEPT_BAD_PHASE) mutated_arguments[3] = "REVIEWER";
    if (test_case == ACCEPT_BAD_PROVIDER) {
        mutated_environment[7] = "FUKUROU_LLM_PROVIDER=codex";
        environment = mutated_environment;
    }
    if (test_case == ACCEPT_BAD_PATH) {
        mutated_environment[1] = "PATH=/tmp:/usr/bin";
        environment = mutated_environment;
    }
    if (test_case == ACCEPT_BAD_HASH) {
        mutated_environment[8] = "FUKUROU_PROMPT_HASH=wrong";
        environment = mutated_environment;
    }
    if (test_case == ACCEPT_UNKNOWN_OPTION) mutated_arguments[1] = "--unknown-option";
    if (test_case == ACCEPT_ARGUMENT_PERMUTATION) {
        mutated_arguments[1] = canary_arguments[2];
        mutated_arguments[2] = canary_arguments[1];
    }
    if (test_case == ACCEPT_DUPLICATE_ARGUMENT) {
        mutated_arguments[4] = canary_arguments[3];
        argument_count = 5;
    }
    if ((test_case >= ACCEPT_PATH_TRAVERSAL && test_case <= ACCEPT_BAD_PHASE) ||
        (test_case >= ACCEPT_UNKNOWN_OPTION && test_case <= ACCEPT_DUPLICATE_ARGUMENT)) arguments = mutated_arguments;
    if (test_case == ACCEPT_ROOT_HOME) {
        environment = root_environment;
        environment_count = 13;
    }

    struct fukurou_launch_header header = {
        .magic = FUKUROU_PROTOCOL_MAGIC,
        .version = FUKUROU_PROTOCOL_VERSION,
        .header_size = sizeof(struct fukurou_launch_header),
        .profile = test_case == ACCEPT_RECEIPT_FAILURE ? FUKUROU_PROFILE_MCP_CURRENT_V1
            : test_case == ACCEPT_VERSION_PROBE ? FUKUROU_PROFILE_CODEX_CURRENT_V1
            : FUKUROU_PROFILE_FOUNDATION_CANARY_V1,
        .argc = argument_count,
        .envc = environment_count,
        .fd_role_bitmap = test_case == ACCEPT_RECEIPT_FAILURE ? 0x3fU : 0x7U,
    };
    header.request_nonce[0] = (unsigned char)(nonce_seed + 1U);
    char payload[FUKUROU_PROTOCOL_MAX_PAYLOAD];
    size_t payload_length = 0;
    for (size_t index = 0; index < argument_count; index++) {
        if (append_launch_item(payload, &payload_length, arguments[index]) != 0) return 125;
    }
    for (size_t index = 0; index < environment_count; index++) {
        if (append_launch_item(payload, &payload_length, environment[index]) != 0) return 125;
    }
    if (test_case == ACCEPT_TRAILING_PAYLOAD) payload[payload_length++] = 'x';
    header.total_length = sizeof(header) + (uint32_t)payload_length;

    uint16_t descriptor_count = test_case == ACCEPT_RECEIPT_FAILURE ? 6 : 3;
    int descriptors[6];
    int submission_pair[2] = {-1, -1};
    for (size_t index = 0; index < (descriptor_count < 3 ? descriptor_count : 3); index++) {
        char template[] = "/tmp/fukurou-accept-selftest-XXXXXX";
        descriptors[index] = mkstemp(template);
        if (descriptors[index] < 0) return 125;
        unlink(template);
    }
    if (test_case == ACCEPT_RECEIPT_FAILURE) {
        descriptors[3] = create_sealed_selftest_descriptor();
        descriptors[4] = create_sealed_selftest_descriptor();
        if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, submission_pair) != 0) return 125;
        descriptors[5] = submission_pair[0];
        if (descriptors[3] < 0 || descriptors[4] < 0) return 125;
    }
    char control[CMSG_SPACE(sizeof(descriptors))] = {0};
    struct iovec vectors[2] = {
        {.iov_base = &header, .iov_len = sizeof(header)},
        {.iov_base = payload, .iov_len = payload_length},
    };
    struct msghdr message = {
        .msg_iov = vectors, .msg_iovlen = 2, .msg_control = control,
        .msg_controllen = CMSG_SPACE(sizeof(int) * descriptor_count),
    };
    struct cmsghdr *control_header = CMSG_FIRSTHDR(&message);
    control_header->cmsg_level = SOL_SOCKET;
    control_header->cmsg_type = SCM_RIGHTS;
    control_header->cmsg_len = CMSG_LEN(sizeof(int) * descriptor_count);
    memcpy(CMSG_DATA(control_header), descriptors, sizeof(int) * descriptor_count);
    ssize_t sent = sendmsg(connection, &message, MSG_NOSIGNAL);
    for (size_t index = 0; index < descriptor_count; index++) close(descriptors[index]);
    if (submission_pair[1] >= 0) close(submission_pair[1]);
    if (sent != (ssize_t)header.total_length) return 125;
    int status = -1;
    ssize_t received = recv(connection, &status, sizeof(status), 0);
    close(connection);
    int expects_close = (test_case >= ACCEPT_PATH_TRAVERSAL && test_case <= ACCEPT_UNKNOWN_ANCILLARY) ||
        test_case == ACCEPT_JOB_FULL;
    if (expects_close) return received == 0 ? 0 : 125;
    if (test_case == ACCEPT_NONCE_REPLAY || test_case == ACCEPT_RECEIPT_FAILURE) {
        return received == (ssize_t)sizeof(status) && status == 125 ? 0 : 125;
    }
    return received == (ssize_t)sizeof(status) && status != 125 ? 0 : 125;
}

static int accept_selftest_case(enum accept_selftest_case test_case, unsigned nonce_seed) {
    char path[96];
    snprintf(path, sizeof(path), "/tmp/fukurou-accept-selftest-%ld.sock", (long)getpid());
    int listener = create_socket(path, 0600);
    if (chmod(path, 0666) != 0) return -1;
    if (test_case == ACCEPT_UNKNOWN_ANCILLARY) {
        int enabled = 1;
        if (setsockopt(listener, SOL_SOCKET, SO_PASSCRED, &enabled, sizeof(enabled)) != 0) return -1;
    }
    if (test_case == ACCEPT_JOB_FULL) {
        for (size_t index = 0; index < MAX_JOBS; index++) jobs[index].pid = 1;
    }
    pid_t sender = fork();
    if (sender < 0) return -1;
    if (sender == 0) _exit(send_accept_selftest_request(path, test_case, nonce_seed));
    accept_launch(listener);
    for (size_t attempt = 0; attempt < 200; attempt++) {
        reap_jobs();
        if (available_job_slot() == 0 || test_case == ACCEPT_JOB_FULL) break;
        usleep(10000);
    }
    int status = 0;
    if (waitpid(sender, &status, 0) != sender) status = 0;
    close(listener);
    unlink(path);
    if (test_case == ACCEPT_JOB_FULL) memset(jobs, 0, sizeof(jobs));
    return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
}

static int protocol_selftest(void) {
    int before = count_open_descriptors();
    if (before < 0) return 125;
    for (size_t attempt = 0; attempt < 1000; attempt++) {
        if (protocol_reject_case((unsigned)(attempt % 9)) != 0) return 125;
    }
    struct launch_request malformed_payload = {0};
    malformed_payload.header.argc = 1;
    malformed_payload.header.total_length = sizeof(malformed_payload.header) + 3;
    uint16_t impossible_length = 32;
    memcpy(malformed_payload.payload, &impossible_length, sizeof(impossible_length));
    char *arguments[2] = {0};
    char *environment[3] = {0};
    if (split_payload(&malformed_payload, arguments, environment) == 0) return 125;
    char *duplicate_environment[] = {"PATH=/opt/java/openjdk/bin:/usr/bin:/bin", "PATH=/bin", NULL};
    char *root_environment[] = {"HOME=/root", NULL};
    char *bad_value_environment[] = {"PATH=\x01", NULL};
    char *missing_invocation_environment[] = {"FUKUROU_INVOCATION_ID=", NULL};
    char *rro_arguments[] = {
        "node", "/usr/local/libexec/fukurou-mcp-canary-client.mjs",
        "0123456789abcdef0123456789abcdef0123456789abcdef", "RISK_REDUCTION_ONLY", "EXIT", NULL,
    };
    struct launch_request rro_request = {.header = {.argc = 5}};
    if (!canary_arguments_allowed(&rro_request, rro_arguments)) return 125;
    rro_arguments[4] = "BUY";
    if (canary_arguments_allowed(&rro_request, rro_arguments)) return 125;
    if (environment_entries_allowed(FUKUROU_PROFILE_MCP_CURRENT_V1, duplicate_environment, 2) ||
        environment_entries_allowed(FUKUROU_PROFILE_CLAUDE_CURRENT_V1, root_environment, 1) ||
        environment_entries_allowed(FUKUROU_PROFILE_CLAUDE_CURRENT_V1, bad_value_environment, 1) ||
        environment_entries_allowed(FUKUROU_PROFILE_MCP_CURRENT_V1, missing_invocation_environment, 1) ||
        executable_for(FUKUROU_PROFILE_CLAUDE_CURRENT_V1, APP_UID, APP_GID) != NULL ||
        executable_for(0xffffU, LLM_UID, LLM_GID) != NULL) return 125;
    struct launch_request mcp_descriptors = {
        .header = {.profile = FUKUROU_PROFILE_MCP_CURRENT_V1},
        .descriptor_count = 6,
    };
    for (size_t index = 0; index < 5; index++) {
        mcp_descriptors.descriptors[index] = create_sealed_selftest_descriptor();
        if (mcp_descriptors.descriptors[index] < 0) return 125;
    }
    int mcp_submission_pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, mcp_submission_pair) != 0) return 125;
    mcp_descriptors.descriptors[5] = mcp_submission_pair[0];
    if (!descriptors_allowed(&mcp_descriptors)) return 125;
    int submission_socket = mcp_descriptors.descriptors[5];
    mcp_descriptors.descriptors[5] = create_sealed_selftest_descriptor();
    if (descriptors_allowed(&mcp_descriptors)) return 125;
    close(mcp_descriptors.descriptors[5]);
    mcp_descriptors.descriptors[5] = submission_socket;
    int inet_submission_socket = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (inet_submission_socket < 0) return 125;
    mcp_descriptors.descriptors[5] = inet_submission_socket;
    if (descriptors_allowed(&mcp_descriptors)) return 125;
    close(inet_submission_socket);
    mcp_descriptors.descriptors[5] = submission_socket;
    int sealed_manifest = mcp_descriptors.descriptors[3];
    mcp_descriptors.descriptors[3] = submission_socket;
    mcp_descriptors.descriptors[5] = sealed_manifest;
    if (descriptors_allowed(&mcp_descriptors)) return 125;
    mcp_descriptors.descriptors[3] = sealed_manifest;
    mcp_descriptors.descriptors[5] = submission_socket;
    int second_socket_pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, second_socket_pair) != 0) return 125;
    int sealed_password = mcp_descriptors.descriptors[4];
    mcp_descriptors.descriptors[4] = second_socket_pair[0];
    if (descriptors_allowed(&mcp_descriptors)) return 125;
    close(second_socket_pair[0]); close(second_socket_pair[1]);
    mcp_descriptors.descriptors[4] = sealed_password;
    close_request_descriptors(&mcp_descriptors);
    close(mcp_submission_pair[1]);
    struct launch_request duplicate_descriptors = {.descriptor_count = 2};
    duplicate_descriptors.descriptors[0] = open("/dev/null", O_RDONLY | O_CLOEXEC);
    duplicate_descriptors.descriptors[1] = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (descriptors_allowed(&duplicate_descriptors)) return 125;
    close_request_descriptors(&duplicate_descriptors);
    struct launch_request unexpected_descriptor = {.descriptor_count = 1};
    unexpected_descriptor.descriptors[0] = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (descriptors_allowed(&unexpected_descriptor)) return 125;
    close_request_descriptors(&unexpected_descriptor);
    for (size_t index = 0; index < MAX_JOBS; index++) jobs[index].pid = 1;
    if (available_job_slot() != MAX_JOBS) return 125;
    memset(jobs, 0, sizeof(jobs));
    int gate[2];
    if (pipe2(gate, O_CLOEXEC) != 0) return 125;
    struct launch_request failed_receipt = {.descriptor_count = 1};
    failed_receipt.descriptors[0] = open("/dev/null", O_RDONLY | O_CLOEXEC);
    pid_t child = fork();
    if (child < 0) return 125;
    if (child == 0) {
        close(gate[1]);
        char permission;
        read(gate[0], &permission, 1);
        _exit(0);
    }
    close(gate[0]);
    reject_spawned_child(child, gate[1], &failed_receipt);
    launches_enabled = 1;
    for (enum accept_selftest_case test_case = ACCEPT_BASELINE; test_case <= ACCEPT_VERSION_PROBE; test_case++) {
        unsigned nonce_seed = test_case == ACCEPT_NONCE_REPLAY ? ACCEPT_BASELINE : (unsigned)test_case;
        if (accept_selftest_case(test_case, nonce_seed) != 0) return 125;
    }
    reap_jobs();
    if (available_job_slot() != 0) return 125;
    char fixture_reservation[37], fixture_provider[37], fixture_mcp[37];
    if (deterministic_uuid("fukurou-launch-reservation-v1:", "receipt-e2e-fixture", NULL, fixture_reservation) != 0 ||
        deterministic_uuid("fukurou-pid-registration-v1:", "receipt-e2e-fixture", "PROVIDER", fixture_provider) != 0 ||
        deterministic_uuid("fukurou-pid-registration-v1:", "receipt-e2e-fixture", "MCP", fixture_mcp) != 0 ||
        strcmp(fixture_reservation, "02729bd6-add4-67a2-6790-6e36ad77cb00") != 0 ||
        strcmp(fixture_provider, "d168f815-9f99-90ef-15fa-3853e0742173") != 0 ||
        strcmp(fixture_mcp, "f02b4431-a53e-e39d-8d42-d7dba634db87") != 0) return 125;
    int after = count_open_descriptors();
    if (after != before) return 125;
    printf("PROTOCOL_SELFTEST_OK fd_delta=0 child_delta=0 attempts=1000 boundary=real-accept cases=baseline,path-traversal,root-env,bad-path,bad-manifest,bad-phase,bad-provider,bad-hash,unknown-option,permutation,duplicate,trailing-bytes,unknown-ancillary,nonce-replay,job-full,receipt-failure,version-probe,bad-magic,bad-version,bad-header,bad-length,bad-count,missing-fd,msg-ctrunc,duplicate-fd,unexpected-fd,bad-peer,bad-role,duplicate-env,bad-env-value,missing-invocation,payload-length\n");
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--protocol-selftest") == 0) return protocol_selftest();
    if (argc == 7 && strcmp(argv[1], "--deploy-operation-probe") == 0) {
        const char *catalog_hash = argv[2];
        if (strlen(catalog_hash) != 64) return 2;
        for (const char *cursor = catalog_hash; *cursor != '\0'; cursor++) {
            if (!((*cursor >= '0' && *cursor <= '9') || (*cursor >= 'a' && *cursor <= 'f'))) return 2;
        }
        return strcmp(argv[3], "FOUNDATION_PREFLIGHT_V1") == 0 &&
            strcmp(argv[4], "SMOKE_HOOK_FOUNDATION_V1") == 0 &&
            strcmp(argv[5], "CANDIDATE_HOOK_V1") == 0 &&
            strcmp(argv[6], "foundation") == 0 ? 0 : 2;
    }
    if (argc == 4 && strcmp(argv[1], "--receipt-ids") == 0) {
        char reservation[37], registration[37];
        if ((strcmp(argv[3], "PROVIDER") != 0 && strcmp(argv[3], "MCP") != 0) ||
            deterministic_uuid("fukurou-launch-reservation-v1:", argv[2], NULL, reservation) != 0 ||
            deterministic_uuid("fukurou-pid-registration-v1:", argv[2], argv[3], registration) != 0) return 2;
        printf("%s %s\n", reservation, registration);
        return 0;
    }
    if (argc == 4 && strcmp(argv[1], "--control") == 0) return control_client(argv[2], argv[3]);
    if (argc == 4 && strcmp(argv[1], "--fence-write") == 0 && getuid() == 0) {
        char *end = NULL; errno = 0; unsigned long long generation = strtoull(argv[3], &end, 10);
        if (errno != 0 || end == argv[3] || *end != '\0' || generation > INT64_MAX) return 2;
        write_fence(argv[2], generation); return 0;
    }
    if (getpid() != 1 || getuid() != 0) fatal("must run as root PID 1");
    if (argc == 6 && strcmp(argv[1], "--canary-preflight") == 0) return run_canary_preflight(&argv[2]);
    if (argc != 1) return 2;
    signal(SIGPIPE, SIG_IGN);
    if (read_fence() != 0) {
        launches_enabled = 0;
        fprintf(stderr, "fukurou runtime supervisor: invalid fence; launch stays disabled\n");
    }
    if (reconcile_database() != 0) {
        launches_enabled = 0;
        fprintf(stderr, "fukurou runtime supervisor: database/fence mismatch; launch stays disabled\n");
    }
    int control = create_socket(CONTROL_SOCKET, 0600);
    if (launches_enabled) launch_listener = create_socket(FUKUROU_LAUNCH_SOCKET, 0666);
    pid_t application = start_application();
    if (application < 0) fatal("cannot start application");

    for (;;) {
        struct pollfd descriptors[2] = {{control, POLLIN, 0}, {launch_listener, POLLIN, 0}};
        if (poll(descriptors, 2, 200) < 0 && errno != EINTR) fatal("poll failed");
        if (descriptors[0].revents & POLLIN) accept_control(control);
        if (launch_listener >= 0 && descriptors[1].revents & POLLIN) accept_launch(launch_listener);
        reap_jobs();
        int status = 0;
        pid_t result = waitpid(application, &status, WNOHANG);
        if (result == application) return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    }
}
