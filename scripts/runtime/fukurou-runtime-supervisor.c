#define _GNU_SOURCE
#include "fukurou-runtime-protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
#define MAX_JOBS 64

struct launch_request {
    struct fukurou_launch_header header;
    char payload[FUKUROU_PROTOCOL_MAX_PAYLOAD];
    int descriptors[5];
};

struct job {
    pid_t pid;
    int response_fd;
};

static struct job jobs[MAX_JOBS];
static unsigned long long fence_generation = 0;
static int launches_enabled = 0;
static char fence_state[48] = "CORRUPT";

static int read_database_state(unsigned long long *generation, int *maintenance_enabled);
static int reconcile_database(void);

static void fatal(const char *message) {
    fprintf(stderr, "fukurou runtime supervisor: %s: %s\n", message, strerror(errno));
    _exit(125);
}

static uint64_t checksum(const char *value) {
    uint64_t result = 1469598103934665603ULL;
    for (; *value != '\0'; value++) {
        result ^= (unsigned char)*value;
        result *= 1099511628211ULL;
    }
    return result;
}

static void write_fence(const char *state, unsigned long long generation) {
    char payload[256];
    int payload_length = snprintf(payload, sizeof(payload), "version=1\ngeneration=%llu\nstate=%s\n", generation, state);
    if (payload_length <= 0 || (size_t)payload_length >= sizeof(payload)) fatal("cannot format fence");

    char record[320];
    int record_length = snprintf(record, sizeof(record), "%schecksum=%016llx\n", payload,
        (unsigned long long)checksum(payload));
    if (record_length <= 0 || (size_t)record_length >= sizeof(record)) fatal("cannot format fence checksum");

    char temporary[256];
    snprintf(temporary, sizeof(temporary), FENCE_DIRECTORY "/.fence.tmp.%ld", (long)getpid());
    int fd = open(temporary, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) fatal("cannot create fence temp file");
    if (write(fd, record, (size_t)record_length) != record_length || fsync(fd) != 0 || close(fd) != 0) {
        unlink(temporary);
        fatal("cannot persist fence temp file");
    }
    if (rename(temporary, FENCE_FILE) != 0) fatal("cannot replace fence record");
    int directory = open(FENCE_DIRECTORY, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory < 0 || fsync(directory) != 0 || close(directory) != 0) fatal("cannot fsync fence directory");
    snprintf(fence_state, sizeof(fence_state), "%s", state);
}

static int read_fence(void) {
    int fd = open(FENCE_FILE, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    char record[320] = {0};
    ssize_t length = read(fd, record, sizeof(record) - 1);
    close(fd);
    if (length <= 0) return -1;

    unsigned version = 0;
    unsigned long long generation = 0, expected = 0;
    char state[48] = {0};
    if (sscanf(record, "version=%u\ngeneration=%llu\nstate=%47[^\n]\nchecksum=%llx\n",
        &version, &generation, state, &expected) != 4 || version != 1) return -1;
    char payload[256];
    snprintf(payload, sizeof(payload), "version=1\ngeneration=%llu\nstate=%s\n", generation, state);
    if (checksum(payload) != expected) return -1;
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
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0 || chmod(path, mode) != 0 || listen(fd, 32) != 0) {
        fatal("cannot publish socket");
    }
    return fd;
}

static int receive_request(int fd, struct launch_request *request) {
    memset(request, 0, sizeof(*request));
    for (size_t index = 0; index < 5; index++) request->descriptors[index] = -1;
    struct iovec vector[2] = {
        {.iov_base = &request->header, .iov_len = sizeof(request->header)},
        {.iov_base = request->payload, .iov_len = sizeof(request->payload)},
    };
    char control[CMSG_SPACE(sizeof(int) * 5)] = {0};
    struct msghdr message = {
        .msg_iov = vector, .msg_iovlen = 2, .msg_control = control, .msg_controllen = sizeof(control),
    };
    ssize_t received = recvmsg(fd, &message, MSG_CMSG_CLOEXEC);
    if (received < (ssize_t)sizeof(request->header) || (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC))) return -1;
    if (request->header.magic != FUKUROU_PROTOCOL_MAGIC || request->header.payload_size > sizeof(request->payload) ||
        received != (ssize_t)(sizeof(request->header) + request->header.payload_size) ||
        request->header.argc == 0 || request->header.argc > FUKUROU_PROTOCOL_MAX_ITEMS ||
        request->header.envc > FUKUROU_PROTOCOL_MAX_ITEMS || request->header.descriptor_count > 5) return -1;
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    if (header == NULL || header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
        header->cmsg_len != CMSG_LEN(sizeof(int) * request->header.descriptor_count)) return -1;
    memcpy(request->descriptors, CMSG_DATA(header), sizeof(int) * request->header.descriptor_count);
    return 0;
}

static int split_payload(struct launch_request *request, char **arguments, char **environment) {
    size_t offset = 0;
    for (size_t index = 0; index < (size_t)request->header.argc + request->header.envc; index++) {
        if (offset >= request->header.payload_size) return -1;
        char *item = request->payload + offset;
        size_t remaining = request->header.payload_size - offset;
        size_t length = strnlen(item, remaining);
        if (length == remaining) return -1;
        if (index < request->header.argc) arguments[index] = item;
        else environment[index - request->header.argc] = item;
        offset += length + 1;
    }
    if (offset != request->header.payload_size) return -1;
    arguments[request->header.argc] = NULL;
    environment[request->header.envc] = NULL;
    return 0;
}

static const char *executable_for(uint16_t kind, uid_t peer_uid) {
    if (kind == FUKUROU_LAUNCH_CLAUDE && peer_uid == LLM_UID) return "/usr/local/bin/claude";
    if (kind == FUKUROU_LAUNCH_CODEX && peer_uid == LLM_UID) return "/usr/local/bin/codex";
    if (kind == FUKUROU_LAUNCH_CANARY && peer_uid == LLM_UID) return "/usr/bin/node";
    if (kind == FUKUROU_LAUNCH_MCP && peer_uid == MCP_UID) return "/opt/java/openjdk/bin/java";
    return NULL;
}

static int environment_name_allowed(uint16_t kind, const char *entry) {
    static const char *llm_names[] = {
        "PATH=", "HOME=", "CODEX_HOME=", "CLAUDE_CONFIG_DIR=", "LANG=", "LC_ALL=", "TERM=", "TMPDIR=",
        "XDG_CACHE_HOME=", "FUKUROU_INVOCATION_ID=", "FUKUROU_LLM_PROVIDER=", "FUKUROU_PROMPT_HASH=",
        "FUKUROU_SYSTEM_PROMPT_VERSION=", "FUKUROU_MARKET_SNAPSHOT_ID=", "FUKUROU_RUNTIME_CONFIG_VERSION_ID=",
        "FUKUROU_RUNTIME_CONFIG_HASH=", "FUKUROU_FALSIFIER_INTENT_ID=", "FUKUROU_CANARY_INTENT_ID=",
        "FUKUROU_CANARY_LLM_DUMPABLE=", "FUKUROU_CANARY_LLM_CORE_LIMIT=", "FUKUROU_CANARY_LLM_LAUNCH_FDS=",
    };
    if (kind == FUKUROU_LAUNCH_MCP) return strcmp(entry, "PATH=/opt/java/openjdk/bin:/usr/bin:/bin") == 0;
    for (size_t index = 0; index < sizeof(llm_names) / sizeof(llm_names[0]); index++) {
        if (strncmp(entry, llm_names[index], strlen(llm_names[index])) == 0) return 1;
    }
    return 0;
}

static int request_shape_allowed(struct launch_request *request, char **arguments, char **environment) {
    if ((request->header.kind == FUKUROU_LAUNCH_CLAUDE || request->header.kind == FUKUROU_LAUNCH_CODEX) &&
        request->header.descriptor_count == 3 &&
        strcmp(arguments[0], request->header.kind == FUKUROU_LAUNCH_CLAUDE ? "claude" : "codex") == 0) {
        /* Provider arguments remain a provider contract; the executable itself is fixed here. */
    } else if (request->header.kind == FUKUROU_LAUNCH_CANARY && request->header.descriptor_count == 3 &&
        request->header.argc == 4 && strcmp(arguments[0], "node") == 0 &&
        strcmp(arguments[1], "/usr/local/libexec/fukurou-mcp-canary-client.mjs") == 0) {
        /* The launcher validates the two canary identifiers before this proxy boundary. */
    } else if (request->header.kind == FUKUROU_LAUNCH_MCP && request->header.descriptor_count == 5 &&
        request->header.argc == 3 && strcmp(arguments[0], "java") == 0 &&
        strcmp(arguments[1], "-jar") == 0 && strcmp(arguments[2], "/app/fukurou-mcp-all.jar") == 0) {
        /* MCP has a fixed argv contract. */
    } else {
        return 0;
    }
    for (size_t index = 0; index < request->header.envc; index++) {
        if (!environment_name_allowed(request->header.kind, environment[index])) return 0;
    }
    return 1;
}

static void close_request_descriptors(struct launch_request *request) {
    for (size_t index = 0; index < request->header.descriptor_count; index++) {
        if (request->descriptors[index] >= 0) close(request->descriptors[index]);
    }
}

static void accept_launch(int listener) {
    int connection = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
    if (connection < 0) return;
    if (!launches_enabled) {
        int status = 125;
        send(connection, &status, sizeof(status), MSG_NOSIGNAL);
        close(connection);
        return;
    }
    struct ucred credentials;
    socklen_t credential_length = sizeof(credentials);
    if (getsockopt(connection, SOL_SOCKET, SO_PEERCRED, &credentials, &credential_length) != 0) {
        close(connection);
        return;
    }
    struct launch_request request;
    if (receive_request(connection, &request) != 0) {
        close(connection);
        return;
    }
    const char *executable = executable_for(request.header.kind, credentials.uid);
    char *arguments[FUKUROU_PROTOCOL_MAX_ITEMS + 1], *environment[FUKUROU_PROTOCOL_MAX_ITEMS + 1];
    if (executable == NULL || split_payload(&request, arguments, environment) != 0 ||
        !request_shape_allowed(&request, arguments, environment)) {
        close_request_descriptors(&request);
        close(connection);
        return;
    }
    size_t slot = 0;
    while (slot < MAX_JOBS && jobs[slot].pid != 0) slot++;
    if (slot == MAX_JOBS) {
        close_request_descriptors(&request);
        close(connection);
        return;
    }
    pid_t child = fork();
    if (child < 0) {
        close_request_descriptors(&request);
        close(connection);
        return;
    }
    if (child == 0) {
        for (size_t index = 0; index < request.header.descriptor_count; index++) {
            if (dup2(request.descriptors[index], (int)index) < 0) _exit(126);
        }
        if (setgroups(0, NULL) != 0 || setresgid(credentials.gid, credentials.gid, credentials.gid) != 0 ||
            setresuid(credentials.uid, credentials.uid, credentials.uid) != 0) _exit(126);
        close_range(request.header.descriptor_count, ~0U, 0);
        execve(executable, arguments, environment);
        _exit(126);
    }
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
        fence_generation = generation;
        write_fence("DISABLED_PENDING_DB", generation);
    } else if (strcmp(operation, "COMMIT") == 0 && generation == fence_generation) {
        unsigned long long database_generation = 0;
        int maintenance_enabled = 0;
        if (read_database_state(&database_generation, &maintenance_enabled) != 0 ||
            database_generation != generation || !maintenance_enabled) {
            close(connection);
            return;
        }
        write_fence("DISABLED_COMMITTED", generation);
    } else if (strcmp(operation, "ENABLE") == 0) {
        unsigned long long database_generation = 0;
        int maintenance_enabled = 0;
        if (read_database_state(&database_generation, &maintenance_enabled) != 0 ||
            database_generation != generation || maintenance_enabled) {
            close(connection);
            return;
        }
        fence_generation = generation;
        write_fence("ENABLED", generation);
        launches_enabled = 1;
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

static int read_database_state(unsigned long long *generation, int *maintenance_enabled) {
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
    if (sscanf(response, "%llu|%7s", &database_generation, maintenance) != 2) return -1;
    *generation = database_generation;
    *maintenance_enabled = strcmp(maintenance, "true") == 0;
    return 0;
}

static int reconcile_database(void) {
    unsigned long long database_generation = 0;
    int database_disabled = 0;
    if (read_database_state(&database_generation, &database_disabled) != 0 || database_generation != fence_generation) {
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

int main(int argc, char **argv) {
    if (argc == 4 && strcmp(argv[1], "--control") == 0) return control_client(argv[2], argv[3]);
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
    int launch = create_socket(FUKUROU_LAUNCH_SOCKET, 0666);
    pid_t application = start_application();
    if (application < 0) fatal("cannot start application");

    for (;;) {
        struct pollfd descriptors[2] = {{control, POLLIN, 0}, {launch, POLLIN, 0}};
        if (poll(descriptors, 2, 200) < 0 && errno != EINTR) fatal("poll failed");
        if (descriptors[0].revents & POLLIN) accept_control(control);
        if (descriptors[1].revents & POLLIN) accept_launch(launch);
        reap_jobs();
        int status = 0;
        pid_t result = waitpid(application, &status, WNOHANG);
        if (result == application) return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    }
}
