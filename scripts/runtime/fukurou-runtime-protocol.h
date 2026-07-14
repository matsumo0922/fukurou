#ifndef FUKUROU_RUNTIME_PROTOCOL_H
#define FUKUROU_RUNTIME_PROTOCOL_H

#include <stdint.h>

#define FUKUROU_LAUNCH_SOCKET "/run/fukurou/control/supervisor-launch.sock"
#define FUKUROU_PROTOCOL_MAGIC 0x46534c31U
#define FUKUROU_PROTOCOL_VERSION 1U
#define FUKUROU_PROTOCOL_MAX_PAYLOAD (64U * 1024U)
#define FUKUROU_PROTOCOL_MAX_ITEMS 128U
#define FUKUROU_PROTOCOL_NONCE_SIZE 32U

enum fukurou_launch_profile {
    FUKUROU_PROFILE_CLAUDE_CURRENT_V1 = 1,
    FUKUROU_PROFILE_CODEX_CURRENT_V1 = 2,
    FUKUROU_PROFILE_FOUNDATION_CANARY_V1 = 3,
    FUKUROU_PROFILE_MCP_CURRENT_V1 = 4,
};

enum fukurou_fd_role {
    FUKUROU_FD_STDIN = 1U << 0,
    FUKUROU_FD_STDOUT = 1U << 1,
    FUKUROU_FD_STDERR = 1U << 2,
    FUKUROU_FD_MANIFEST = 1U << 3,
    FUKUROU_FD_HANDLE = 1U << 4,
};

struct fukurou_launch_header {
    uint32_t magic;
    uint16_t version;
    uint16_t header_size;
    uint32_t total_length;
    uint16_t profile;
    uint16_t argc;
    uint16_t envc;
    uint16_t fd_role_bitmap;
    unsigned char request_nonce[FUKUROU_PROTOCOL_NONCE_SIZE];
};

#endif
