#define _GNU_SOURCE
#include <errno.h>
#include <ctype.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/securebits.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/memfd.h>
#include <sys/mman.h>
#include <linux/capability.h>
#include <sys/fsuid.h>
#include <sys/syscall.h>
#include <sys/socket.h>
#include <sys/un.h>
#include "fukurou-runtime-proxy.h"

#define APP_UID 10001
#define MCP_UID 10003
#define MCP_GID 10003
#define MANIFEST_DIRECTORY "/run/fukurou/mcp-manifests"
#define PASSWORD_FILE "/run/secrets/fukurou_mcp_db_password"
#define CANARY_FLAG "/run/secrets/fukurou_canary_enabled"
#define MCP_JAR "/app/fukurou-mcp-all.jar"

static void fail(const char *message) {
    fprintf(stderr, "fukurou mcp launcher: %s\n", message);
    _exit(126);
}

static int valid_id(const char *id) {
    if (strlen(id) != 48) return 0;
    for (size_t index = 0; index < 48; index++) {
        if (!((id[index] >= '0' && id[index] <= '9') || (id[index] >= 'a' && id[index] <= 'f'))) return 0;
    }
    return 1;
}

static void ensure_standard_descriptors(void) {
    for (int descriptor = 0; descriptor <= 2; descriptor++) {
        if (fcntl(descriptor, F_GETFD) >= 0) continue;
        if (errno != EBADF) fail("cannot inspect standard descriptors");
        int replacement = open("/dev/null", O_RDWR);
        if (replacement != descriptor) fail("cannot restore standard descriptors");
    }
}

static int safe_open_manifest(const char *id) {
    int directory = open(MANIFEST_DIRECTORY, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory < 0) fail("manifest directory unavailable");
    char name[54];
    snprintf(name, sizeof(name), "%s.json", id);
    int fd = openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    close(directory);
    if (fd < 0) fail("manifest unavailable");
    struct stat metadata;
    if (fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode)) fail("manifest is not a regular file");
    if (metadata.st_uid != 10001 || (metadata.st_mode & 0777) != 0600 || metadata.st_nlink != 1) fail("manifest metadata rejected");
    return fd;
}

static int safe_open_password(void) {
    int fd = open(PASSWORD_FILE, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) fail("password file unavailable");
    struct stat metadata;
    if (fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode)) fail("password is not a regular file");
    if (metadata.st_uid != 0 || (metadata.st_mode & 0777) != 0400 || metadata.st_nlink != 1) fail("password metadata rejected");
    return fd;
}

static int connect_submission_gateway(const char *id) {
    char path[sizeof(((struct sockaddr_un *)0)->sun_path)];
    int count = snprintf(path, sizeof(path), "%s/%s.sock", MANIFEST_DIRECTORY, id);
    if (count < 0 || (size_t)count >= sizeof(path)) fail("submission gateway path rejected");
    struct stat metadata;
    if (lstat(path, &metadata) != 0 || !S_ISSOCK(metadata.st_mode)) fail("submission gateway is not a socket");
    if (metadata.st_uid != 10001 || (metadata.st_mode & 0777) != 0600 || metadata.st_nlink != 1) {
        fail("submission gateway metadata rejected");
    }
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) fail("cannot create submission gateway socket");
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path, (size_t)count + 1);
    setfsuid(APP_UID);
    if (setfsuid((uid_t)-1) != APP_UID) fail("cannot assume submission gateway owner identity");
    int connect_status = connect(fd, (struct sockaddr *)&address, sizeof(address));
    int connect_error = errno;
    setfsuid(0);
    if (setfsuid((uid_t)-1) != 0) fail("cannot restore launcher filesystem identity");
    if (connect_status != 0) fail(strerror(connect_error));
    return fd;
}

static int copy_to_mcp_memfd(int source, const char *name, size_t maximum) {
    int target = memfd_create(name, MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (target < 0) fail("cannot create bootstrap memory file");
    char buffer[4096];
    size_t total = 0;
    for (;;) {
        ssize_t count = read(source, buffer, sizeof(buffer));
        if (count < 0) fail("cannot read bootstrap source");
        if (count == 0) break;
        total += (size_t)count;
        if (total > maximum) fail("bootstrap source is too large");
        ssize_t written = write(target, buffer, (size_t)count);
        if (written != count) fail("cannot copy bootstrap source");
    }
    if (total == 0 || lseek(target, 0, SEEK_SET) < 0) fail("bootstrap source is empty");
    if (fchmod(target, 0400) != 0 || fchown(target, MCP_UID, MCP_GID) != 0) fail("cannot secure bootstrap memory file");
    if (fcntl(target, F_ADD_SEALS, F_SEAL_SEAL | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE) != 0) fail("cannot seal bootstrap memory file");
    return target;
}

static void drop_capability_bounding_set(void) {
    for (int capability = 0; capability <= CAP_LAST_CAP; capability++) {
        if (prctl(PR_CAPBSET_DROP, capability, 0, 0, 0) != 0 && errno != EINVAL) fail("cannot drop capability bounding set");
    }
}

static int canary_enabled(void) {
    struct stat metadata;
    return lstat(CANARY_FLAG, &metadata) == 0 && S_ISREG(metadata.st_mode) &&
        metadata.st_uid == 0 && (metadata.st_mode & 0777) == 0400 && metadata.st_nlink == 1;
}

static void verify_final_security_state(int canary) {
    uid_t real_uid, effective_uid, saved_uid;
    gid_t real_gid, effective_gid, saved_gid;
    if (getresuid(&real_uid, &effective_uid, &saved_uid) != 0 ||
        real_uid != MCP_UID || effective_uid != MCP_UID || saved_uid != MCP_UID) fail("uid invariant rejected");
    if (getresgid(&real_gid, &effective_gid, &saved_gid) != 0 ||
        real_gid != MCP_GID || effective_gid != MCP_GID || saved_gid != MCP_GID) fail("gid invariant rejected");
    if (setfsuid((uid_t)-1) != MCP_UID || setfsgid((gid_t)-1) != MCP_GID) fail("filesystem id invariant rejected");
    if (getgroups(0, NULL) != 0) fail("supplementary group invariant rejected");
    if (prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) != 1) fail("no_new_privs invariant rejected");
    struct __user_cap_header_struct capability_header = {_LINUX_CAPABILITY_VERSION_3, 0};
    struct __user_cap_data_struct capability_data[2] = {{0}};
    if (syscall(SYS_capget, &capability_header, capability_data) != 0) fail("cannot inspect final capabilities");
    for (size_t index = 0; index < 2; index++) {
        if (capability_data[index].effective != 0 || capability_data[index].permitted != 0 ||
            capability_data[index].inheritable != 0) fail("active capability invariant rejected");
    }
    for (int capability = 0; capability <= CAP_LAST_CAP; capability++) {
        if (prctl(PR_CAPBSET_READ, capability, 0, 0, 0) == 1) fail("capability bounding invariant rejected");
        if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, capability, 0, 0) == 1) fail("ambient capability invariant rejected");
    }
    for (int descriptor = 0; descriptor <= 5; descriptor++) {
        if (fcntl(descriptor, F_GETFD) < 0) fail("required descriptor invariant rejected");
    }
    for (int descriptor = 6; descriptor <= 64; descriptor++) {
        if (fcntl(descriptor, F_GETFD) >= 0 || errno != EBADF) fail("unexpected descriptor invariant rejected");
    }
    if (canary) fprintf(stderr, "MCP_LAUNCHER_PROBE uid=10003 gid=10003 fsuid=10003 fsgid=10003 groups=empty nnp=1 caps=zero capbnd=zero fds=0,1,2,3,4,5\n");
}

int main(int argc, char **argv) {
    if (argc != 2 || !valid_id(argv[1])) fail("exactly one canonical manifest id is required");
    ensure_standard_descriptors();
    int canary = canary_enabled();
    int manifest = safe_open_manifest(argv[1]);
    int password = safe_open_password();
    int submission_gateway = connect_submission_gateway(argv[1]);
    int manifest_source = copy_to_mcp_memfd(manifest, "fukurou-mcp-manifest", 64 * 1024);
    int password_source = copy_to_mcp_memfd(password, "fukurou-mcp-password", 4096);
    close(manifest); close(password);
    if (manifest_source < 0 || password_source < 0) fail("cannot duplicate bootstrap descriptors");
    if (dup2(manifest_source, 3) < 0 || dup2(password_source, 4) < 0 || dup2(submission_gateway, 5) < 0) {
        fail("cannot install bootstrap descriptors");
    }
    close(manifest_source); close(password_source);
    if (submission_gateway != 5) close(submission_gateway);
    if (fcntl(3, F_SETFD, 0) != 0 || fcntl(4, F_SETFD, 0) != 0 || fcntl(5, F_SETFD, 0) != 0) {
        fail("cannot preserve bootstrap descriptors");
    }
    if (close_range(6, ~0U, 0) != 0) fail("cannot close inherited descriptors");

    struct rlimit core = {0, 0};
    if (setrlimit(RLIMIT_CORE, &core) != 0) fail("cannot disable core dumps");
    if (prctl(PR_SET_SECUREBITS, SECBIT_KEEP_CAPS_LOCKED) != 0) fail("cannot lock capability retention");
    drop_capability_bounding_set();
    if (setgroups(0, NULL) != 0) fail("cannot clear supplementary groups");
    if (setresgid(MCP_GID, MCP_GID, MCP_GID) != 0) fail("cannot drop gid");
    if (setresuid(MCP_UID, MCP_UID, MCP_UID) != 0) fail("cannot drop uid");
    if (prctl(PR_SET_DUMPABLE, 0) != 0) fail("cannot disable dumpability");
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) fail("cannot set no_new_privs");
    verify_final_security_state(canary);

    const char *invocation_id = getenv("FUKUROU_INVOCATION_ID");
    if (invocation_id == NULL || strlen(invocation_id) == 0 || strlen(invocation_id) > 128) fail("invocation id is required");
    for (const char *cursor = invocation_id; *cursor != '\0'; cursor++) {
        if (!(isalnum((unsigned char)*cursor) || *cursor == '.' || *cursor == '_' || *cursor == ':' || *cursor == '-')) {
            fail("invocation id is malformed");
        }
    }
    char invocation_environment[160];
    if (snprintf(invocation_environment, sizeof(invocation_environment), "FUKUROU_INVOCATION_ID=%s", invocation_id) >=
        (int)sizeof(invocation_environment)) fail("invocation id is too long");
    char *const args[] = {
        "java",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "-jar",
        MCP_JAR,
        NULL
    };
    char *const environment[] = {"PATH=/opt/java/openjdk/bin:/usr/bin:/bin", invocation_environment, NULL};
    _exit(fukurou_supervisor_proxy(FUKUROU_PROFILE_MCP_CURRENT_V1, args, environment, 6));
}
