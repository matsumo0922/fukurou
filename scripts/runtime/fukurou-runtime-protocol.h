#ifndef FUKUROU_RUNTIME_PROTOCOL_H
#define FUKUROU_RUNTIME_PROTOCOL_H

#include <stdint.h>

#define FUKUROU_LAUNCH_SOCKET "/run/fukurou/control/supervisor-launch.sock"
#define FUKUROU_PROTOCOL_MAGIC 0x46534c31U
#define FUKUROU_PROTOCOL_MAX_PAYLOAD (64U * 1024U)
#define FUKUROU_PROTOCOL_MAX_ITEMS 128U

enum fukurou_launch_kind {
    FUKUROU_LAUNCH_CLAUDE = 1,
    FUKUROU_LAUNCH_CODEX = 2,
    FUKUROU_LAUNCH_CANARY = 3,
    FUKUROU_LAUNCH_MCP = 4,
};

struct fukurou_launch_header {
    uint32_t magic;
    uint16_t kind;
    uint16_t argc;
    uint16_t envc;
    uint16_t descriptor_count;
    uint32_t payload_size;
};

#endif
