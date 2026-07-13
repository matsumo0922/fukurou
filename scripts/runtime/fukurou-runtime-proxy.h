#ifndef FUKUROU_RUNTIME_PROXY_H
#define FUKUROU_RUNTIME_PROXY_H

#include "fukurou-runtime-protocol.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/random.h>
#include <sys/un.h>
#include <unistd.h>

static int fukurou_supervisor_proxy(
    enum fukurou_launch_profile profile,
    char *const arguments[],
    char *const environment[],
    uint16_t descriptor_count
) {
    struct fukurou_launch_header header = {
        .magic = FUKUROU_PROTOCOL_MAGIC,
        .version = FUKUROU_PROTOCOL_VERSION,
        .header_size = sizeof(struct fukurou_launch_header),
        .profile = (uint16_t)profile,
        .fd_role_bitmap = descriptor_count == 3 ? 0x7U : descriptor_count == 5 ? 0x1fU : 0U,
    };
    if ((descriptor_count != 3 && descriptor_count != 5) || getrandom(header.request_nonce, sizeof(header.request_nonce), 0) != sizeof(header.request_nonce)) return 126;
    char payload[FUKUROU_PROTOCOL_MAX_PAYLOAD];
    size_t payload_size = 0;
    for (size_t index = 0; arguments[index] != NULL; index++) {
        if (index >= FUKUROU_PROTOCOL_MAX_ITEMS) return 126;
        size_t length = strlen(arguments[index]);
        if (length > UINT16_MAX || payload_size + sizeof(uint16_t) + length + 1 > sizeof(payload)) return 126;
        uint16_t encoded_length = (uint16_t)length;
        memcpy(payload + payload_size, &encoded_length, sizeof(encoded_length)); payload_size += sizeof(encoded_length);
        memcpy(payload + payload_size, arguments[index], length + 1); payload_size += length + 1;
        header.argc++;
    }
    for (size_t index = 0; environment[index] != NULL; index++) {
        if (index >= FUKUROU_PROTOCOL_MAX_ITEMS) return 126;
        size_t length = strlen(environment[index]);
        if (length > UINT16_MAX || payload_size + sizeof(uint16_t) + length + 1 > sizeof(payload)) return 126;
        uint16_t encoded_length = (uint16_t)length;
        memcpy(payload + payload_size, &encoded_length, sizeof(encoded_length)); payload_size += sizeof(encoded_length);
        memcpy(payload + payload_size, environment[index], length + 1); payload_size += length + 1;
        header.envc++;
    }
    header.total_length = (uint32_t)(sizeof(header) + payload_size);

    int socket_fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (socket_fd < 0) return 126;
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strcpy(address.sun_path, FUKUROU_LAUNCH_SOCKET);
    if (connect(socket_fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        close(socket_fd);
        return 125;
    }
    struct iovec vector[2] = {
        {.iov_base = &header, .iov_len = sizeof(header)},
        {.iov_base = payload, .iov_len = payload_size},
    };
    char control[CMSG_SPACE(sizeof(int) * 5)] = {0};
    struct msghdr message = {.msg_iov = vector, .msg_iovlen = 2};
    if (descriptor_count > 0) {
        message.msg_control = control;
        message.msg_controllen = CMSG_SPACE(sizeof(int) * descriptor_count);
        struct cmsghdr *control_header = CMSG_FIRSTHDR(&message);
        control_header->cmsg_level = SOL_SOCKET;
        control_header->cmsg_type = SCM_RIGHTS;
        control_header->cmsg_len = CMSG_LEN(sizeof(int) * descriptor_count);
        int descriptors[5] = {0, 1, 2, 3, 4};
        memcpy(CMSG_DATA(control_header), descriptors, sizeof(int) * descriptor_count);
    }
    if (sendmsg(socket_fd, &message, MSG_NOSIGNAL) != (ssize_t)(sizeof(header) + payload_size)) {
        close(socket_fd);
        return 126;
    }
    int status = 126;
    ssize_t received;
    do {
        received = recv(socket_fd, &status, sizeof(status), 0);
    } while (received < 0 && errno == EINTR);
    close(socket_fd);
    return received == sizeof(status) ? status : 126;
}

#endif
