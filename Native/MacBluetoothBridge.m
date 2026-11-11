// macOS native Bluetooth Classic RFCOMM adapter for iap2loc.
// Copyright (C) 2026, modifications released under GPL-3.0-or-later.
// Build: clang -fobjc-arc -fPIC -dynamiclib -framework Foundation -framework IOBluetooth
#import <Foundation/Foundation.h>
#import <IOBluetooth/IOBluetooth.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <time.h>

#define MAX_RX_BUFFER (4 * 1024 * 1024)
static const uint8_t IAP2_UUID[16] = {
    0x00, 0x00, 0x00, 0x00, 0xDE, 0xCA, 0xFA, 0xDE,
    0xDE, 0xCA, 0xDE, 0xAF, 0xDE, 0xCA, 0xCA, 0xFE
};

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    pthread_t worker;
    int fdNative;
    int fdClient;
    int timeoutMs;
    int ready;
    int callerAck;
    int success;
    int abortRequested;
    char address[32];
    char error[512];
} BTContext;

static void set_error(BTContext *ctx, const char *reason, int status) {
    snprintf(ctx->error, sizeof(ctx->error), "%s (code=0x%08x)", reason, (unsigned int)status);
}

static int should_abort(BTContext *ctx) {
    pthread_mutex_lock(&ctx->mutex);
    int abort = ctx->abortRequested;
    pthread_mutex_unlock(&ctx->mutex);
    return abort;
}

static double monotonic_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static void pump_runloop(void) {
    @autoreleasepool {
        BOOL handled = [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                             beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.015]];
        if (!handled) usleep(10000); // Do not spin if no runloop sources are registered.
    }
}

@interface Iap2BtEvents : NSObject <IOBluetoothRFCOMMChannelDelegate>
@property(nonatomic, assign) BOOL sdpDone;
@property(nonatomic, assign) IOReturn sdpResult;
@property(nonatomic, assign) BOOL openDone;
@property(nonatomic, assign) IOReturn openResult;
@property(nonatomic, assign) BOOL closed;
@property(nonatomic, strong) NSMutableData *pendingReceive;
@end

@implementation Iap2BtEvents
- (instancetype)init {
    self = [super init];
    if (self) _pendingReceive = [NSMutableData data];
    return self;
}
- (void)sdpQueryComplete:(IOBluetoothDevice *)device status:(IOReturn)status {
    (void)device;
    _sdpResult = status;
    _sdpDone = YES;
}
- (void)rfcommChannelOpenComplete:(IOBluetoothRFCOMMChannel *)channel status:(IOReturn)status {
    (void)channel;
    _openResult = status;
    _openDone = YES;
}
- (void)rfcommChannelData:(IOBluetoothRFCOMMChannel *)channel data:(void *)bytes length:(size_t)length {
    (void)channel;
    if (length == 0) return;
    if ([_pendingReceive length] + length > MAX_RX_BUFFER) {
        _closed = YES; // Never grow without a bound if a consumer stops reading.
        return;
    }
    [_pendingReceive appendBytes:bytes length:length];
}
- (void)rfcommChannelClosed:(IOBluetoothRFCOMMChannel *)channel {
    (void)channel;
    _closed = YES;
}
@end

static BOOL wait_for_flag(BTContext *ctx, Iap2BtEvents *events,
                          SEL completionSelector, double deadline) {
    while (!should_abort(ctx) && monotonic_seconds() < deadline) {
        BOOL finished = completionSelector == @selector(sdpQueryComplete:status:)
            ? events.sdpDone : events.openDone;
        if (finished) return YES;
        pump_runloop();
    }
    return NO;
}

static void *bt_thread(void *opaque) {
    BTContext *ctx = (BTContext *)opaque;
    @autoreleasepool {
        IOBluetoothDevice *device = nil;
        IOBluetoothRFCOMMChannel *channel = nil;
        IOBluetoothSDPUUID *uuid = nil;
        IOBluetoothSDPServiceRecord *record = nil;
        Iap2BtEvents *events = [[Iap2BtEvents alloc] init];
        double deadline = monotonic_seconds() + (double)ctx->timeoutMs / 1000.0;
        BOOL connected = NO;
        IOReturn status = kIOReturnError;

        device = [IOBluetoothDevice deviceWithAddressString:
                    [NSString stringWithUTF8String:ctx->address]];
        if (!device) {
            set_error(ctx, "蓝牙地址不存在或设备无法被系统识别", 1);
            goto finish_connect;
        }
        if (![device isPaired]) {
            set_error(ctx, "请先在 macOS 蓝牙设置中配对目标设备", 2);
            goto finish_connect;
        }

        uuid = [IOBluetoothSDPUUID uuidWithBytes:IAP2_UUID length:16];
        if (!uuid) {
            set_error(ctx, "无法构建 iAP2 服务 UUID", 3);
            goto finish_connect;
        }
        status = [device performSDPQuery:events uuids:@[uuid]];
        if (status != kIOReturnSuccess) {
            set_error(ctx, "iAP2 SDP 服务查询未能启动", status);
            goto finish_connect;
        }
        if (!wait_for_flag(ctx, events, @selector(sdpQueryComplete:status:), deadline)) {
            set_error(ctx, "iAP2 SDP 查询超时或被取消", 4);
            goto finish_connect;
        }
        if (events.sdpResult != kIOReturnSuccess) {
            set_error(ctx, "目标设备拒绝 SDP 服务查询", events.sdpResult);
            goto finish_connect;
        }
        record = [device getServiceRecordForUUID:uuid];
        if (!record) {
            set_error(ctx, "设备 SDP 列表中没有 iAP2 RFCOMM 服务 UUID，无法连接", 5);
            goto finish_connect;
        }
        BluetoothRFCOMMChannelID channelId = 0;
        status = [record getRFCOMMChannelID:&channelId];
        if (status != kIOReturnSuccess || channelId == 0) {
            set_error(ctx, "iAP2 SDP 服务没有可用 RFCOMM channel ID", status);
            goto finish_connect;
        }
        status = [device openRFCOMMChannelAsync:&channel withChannelID:channelId delegate:events];
        if (status != kIOReturnSuccess) {
            set_error(ctx, "无法启动 RFCOMM 通道连接", status);
            goto finish_connect;
        }
        if (!wait_for_flag(ctx, events, @selector(rfcommChannelOpenComplete:status:), deadline)) {
            set_error(ctx, "RFCOMM 连接超时或被取消", 6);
            goto finish_connect;
        }
        if (events.openResult != kIOReturnSuccess || ![channel isOpen]) {
            set_error(ctx, "RFCOMM 连接被拒绝", events.openResult);
            goto finish_connect;
        }
        connected = YES;

finish_connect:
        pthread_mutex_lock(&ctx->mutex);
        ctx->success = connected;
        ctx->ready = 1;
        pthread_cond_broadcast(&ctx->cond);
        // Prevent worker cleanup from racing the caller's state inspection.
        while (!ctx->callerAck) pthread_cond_wait(&ctx->cond, &ctx->mutex);
        pthread_mutex_unlock(&ctx->mutex);

        if (connected) {
            // Worker owns RFCOMM callbacks, IOBluetooth runloop, and native socket.
            // The managed client owns the other socketpair end.
            int mtu = (int)[channel getMTU];
            if (mtu < 1) mtu = 127;
            if (mtu > 65535) mtu = 65535;
            uint8_t buffer[4096];
            while (!events.closed && !should_abort(ctx)) {
                pump_runloop();
                if (events.closed) break;

                // Bluetooth -> managed Socket: drain queued callback data.
                NSUInteger rxCount = [events.pendingReceive length];
                if (rxCount > 0) {
                    ssize_t sent = send(ctx->fdNative, [events.pendingReceive bytes],
                                        MIN(rxCount, (NSUInteger)65536), 0);
                    if (sent > 0) {
                        [events.pendingReceive replaceBytesInRange:NSMakeRange(0, (NSUInteger)sent)
                                                         withBytes:NULL length:0];
                    } else if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                        break;
                    }
                }

                // Managed Socket -> Bluetooth: chunks must not exceed RFCOMM MTU.
                ssize_t got = recv(ctx->fdNative, buffer, sizeof(buffer), MSG_DONTWAIT);
                if (got == 0) break;
                if (got < 0) {
                    if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) break;
                } else {
                    size_t offset = 0;
                    while (offset < (size_t)got) {
                        int count = (int)MIN((size_t)mtu, (size_t)got - offset);
                        status = [channel writeSync:buffer + offset length:(UInt16)count];
                        if (status != kIOReturnSuccess) {
                            events.closed = YES;
                            break;
                        }
                        offset += (size_t)count;
                    }
                }
            }
        }

        if (channel) {
            [channel setDelegate:nil];
            [channel closeChannel];
        }
        shutdown(ctx->fdNative, SHUT_RDWR);
        close(ctx->fdNative);
    }

    if (ctx->success) {
        pthread_cond_destroy(&ctx->cond);
        pthread_mutex_destroy(&ctx->mutex);
        free(ctx);
    }
    return NULL;
}

// Copies UTF-8 output and returns byte count, or -1 on error/insufficient capacity.
static int copy_utf8(NSString *value, char *buffer, int capacity) {
    if (!buffer || capacity <= 0) return -1;
    const char *str = [value UTF8String];
    if (!str) str = "";
    size_t needed = strlen(str);
    if (needed + 1 > (size_t)capacity) return -1;
    memcpy(buffer, str, needed + 1);
    return (int)needed;
}

__attribute__((visibility("default")))
int iap2mac_paired_devices(char *buffer, int capacity) {
    @autoreleasepool {
        NSMutableArray *list = [NSMutableArray array];
        for (IOBluetoothDevice *device in [IOBluetoothDevice pairedDevices] ?: @[]) {
            NSString *address = device.addressString ?: @"";
            NSString *name = device.nameOrAddress ?: address;
            [list addObject:@{
                @"address": address,
                @"name": name,
                @"paired": @([device isPaired]),
                @"connected": @([device isConnected])
            }];
        }
        NSData *json = [NSJSONSerialization dataWithJSONObject:list options:0 error:nil];
        if (!json || !buffer || capacity < (int)[json length] + 1) return -1;
        memcpy(buffer, [json bytes], [json length]);
        buffer[[json length]] = 0;
        return (int)[json length];
    }
}

__attribute__((visibility("default")))
int iap2mac_local_address(char *buffer, int capacity) {
    @autoreleasepool {
        IOBluetoothHostController *controller = [IOBluetoothHostController defaultController];
        NSString *address = [controller addressAsString];
        return copy_utf8(address ?: @"", buffer, capacity);
    }
}

// Returns a *blocking* local stream-socket file descriptor owned by the caller.
// -1 means the remote RFCOMM connection was not established; error explains why.
__attribute__((visibility("default")))
int iap2mac_open(const char *address, int timeoutMs, char *error, int capacity) {
    if (error && capacity > 0) error[0] = 0;
    if (!address || strlen(address) >= 31) {
        copy_utf8(@"蓝牙地址格式错误", error, capacity);
        return -1;
    }

    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds) != 0) {
        copy_utf8(@"本地 socketpair 创建失败", error, capacity);
        return -1;
    }

    BTContext *ctx = calloc(1, sizeof(BTContext));
    if (!ctx) {
        close(fds[0]); close(fds[1]);
        return -1;
    }
    pthread_mutex_init(&ctx->mutex, NULL);
    pthread_cond_init(&ctx->cond, NULL);
    ctx->fdClient = fds[0];
    ctx->fdNative = fds[1];
    ctx->timeoutMs = timeoutMs < 1000 ? 1000 : (timeoutMs > 120000 ? 120000 : timeoutMs);
    strncpy(ctx->address, address, sizeof(ctx->address)-1);

    // Avoid blocking the IOBluetooth runloop on a full client-side socket.
    int flags = fcntl(ctx->fdNative, F_GETFL, 0);
    fcntl(ctx->fdNative, F_SETFL, flags | O_NONBLOCK);
    int yes = 1;
    setsockopt(ctx->fdNative, SOL_SOCKET, SO_NOSIGPIPE, &yes, sizeof(yes));

    if (pthread_create(&ctx->worker, NULL, bt_thread, ctx) != 0) {
        copy_utf8(@"无法创建蓝牙工作线程", error, capacity);
        close(fds[0]); close(fds[1]);
        pthread_cond_destroy(&ctx->cond);
        pthread_mutex_destroy(&ctx->mutex);
        free(ctx);
        return -1;
    }

    pthread_mutex_lock(&ctx->mutex);
    while (!ctx->ready) pthread_cond_wait(&ctx->cond, &ctx->mutex);
    int result = ctx->success ? ctx->fdClient : -1;
    if (!ctx->success) {
        if (error && capacity > 0) {
            strncpy(error, ctx->error, (size_t)capacity - 1);
            error[capacity-1] = 0;
        }
        close(ctx->fdClient);
    }
    pthread_t worker = ctx->worker; // ctx may be freed by the worker after acknowledgement.
    ctx->callerAck = 1;
    pthread_cond_broadcast(&ctx->cond);
    pthread_mutex_unlock(&ctx->mutex);

    if (result >= 0) {
        pthread_detach(worker);
    } else {
        pthread_join(worker, NULL);
        pthread_cond_destroy(&ctx->cond);
        pthread_mutex_destroy(&ctx->mutex);
        free(ctx);
    }
    return result;
}
