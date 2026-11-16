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
#define SDP_TIMEOUT_SECONDS 8.0
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

// IOBluetooth delivers delegate callbacks on the main thread's runloop. The host
// process runs iap2mac_run_main_loop() on its main thread; every IOBluetooth call is
// dispatched onto the main queue, and the per-connection worker thread only waits on
// callback state and moves bytes between the RFCOMM channel and the socketpair.
static volatile int g_main_loop_running = 0;
static volatile int g_main_loop_stop = 0;

@interface Iap2BtEvents : NSObject <IOBluetoothRFCOMMChannelDelegate>
@property(atomic, assign) BOOL sdpDone;
@property(atomic, assign) IOReturn sdpResult;
@property(atomic, assign) BOOL openDone;
@property(atomic, assign) IOReturn openResult;
@property(atomic, assign) BOOL closed;
@property(nonatomic, strong) NSMutableData *pendingReceive; // guarded by @synchronized(self)
@end

@implementation Iap2BtEvents
- (instancetype)init {
    self = [super init];
    if (self) _pendingReceive = [NSMutableData data];
    return self;
}
- (void)sdpQueryComplete:(IOBluetoothDevice *)device status:(IOReturn)status {
    (void)device;
    self.sdpResult = status;
    self.sdpDone = YES;
}
- (void)rfcommChannelOpenComplete:(IOBluetoothRFCOMMChannel *)channel status:(IOReturn)status {
    (void)channel;
    self.openResult = status;
    self.openDone = YES;
}
- (void)rfcommChannelData:(IOBluetoothRFCOMMChannel *)channel data:(void *)bytes length:(size_t)length {
    (void)channel;
    if (length == 0) return;
    @synchronized(self) {
        if ([_pendingReceive length] + length > MAX_RX_BUFFER) {
            self.closed = YES; // Never grow without a bound if a consumer stops reading.
            return;
        }
        [_pendingReceive appendBytes:bytes length:length];
    }
}
- (void)rfcommChannelClosed:(IOBluetoothRFCOMMChannel *)channel {
    (void)channel;
    self.closed = YES;
}
@end

// Runs block on the main thread so that IOBluetooth is only ever used from one thread.
// Without a running main loop (no host cooperation) the block runs on the caller.
static void on_main(dispatch_block_t block) {
    if (pthread_main_np() || !g_main_loop_running) block();
    else dispatch_sync(dispatch_get_main_queue(), block);
}

static BOOL wait_until(BTContext *ctx, BOOL (^finished)(void), double deadline) {
    while (!should_abort(ctx) && monotonic_seconds() < deadline) {
        if (finished()) return YES;
        usleep(10000);
    }
    return finished();
}

static void *bt_thread(void *opaque) {
    BTContext *ctx = (BTContext *)opaque;
    @autoreleasepool {
        __block IOBluetoothDevice *device = nil;
        __block IOBluetoothRFCOMMChannel *channel = nil;
        __block IOBluetoothSDPServiceRecord *record = nil;
        __block BOOL paired = NO;
        __block IOReturn status = kIOReturnError;
        __block BluetoothRFCOMMChannelID channelId = 0;
        __block BOOL isOpen = NO;
        __block int mtu = 0;
        __block IOBluetoothSDPUUID *uuid = [IOBluetoothSDPUUID uuidWithBytes:IAP2_UUID length:16];
        __block Iap2BtEvents *events = [[Iap2BtEvents alloc] init];
        double deadline = monotonic_seconds() + (double)ctx->timeoutMs / 1000.0;
        BOOL connected = NO;
        __block NSString *address = [NSString stringWithUTF8String:ctx->address];

        on_main(^{
            device = [IOBluetoothDevice deviceWithAddressString:address];
            paired = device ? [device isPaired] : NO;
        });
        if (!device) {
            set_error(ctx, "蓝牙地址不存在或设备无法被系统识别", 1);
            goto finish_connect;
        }
        if (!paired) {
            set_error(ctx, "目标设备未与本机配对", 2);
            goto finish_connect;
        }
        if (!uuid) {
            set_error(ctx, "无法构建 iAP2 服务 UUID", 3);
            goto finish_connect;
        }

        // macOS caches SDP records per device (including records synced with iCloud
        // pairing). The cached iAP2 record is used directly; a live SDP query runs only
        // when no cached record exists. An unanswered live query keeps the baseband
        // link busy and blocks the following RFCOMM open, so it is bounded by
        // SDP_TIMEOUT_SECONDS.
        on_main(^{ record = [device getServiceRecordForUUID:uuid]; });
        if (!record) {
            on_main(^{ status = [device performSDPQuery:events uuids:@[uuid]]; });
            if (status == kIOReturnSuccess) {
                double sdpDeadline = MIN(deadline, monotonic_seconds() + SDP_TIMEOUT_SECONDS);
                wait_until(ctx, ^BOOL { return events.sdpDone; }, sdpDeadline);
                if (should_abort(ctx)) {
                    set_error(ctx, "iAP2 SDP 查询被取消", 4);
                    goto finish_connect;
                }
            }
            on_main(^{ record = [device getServiceRecordForUUID:uuid]; });
        }
        if (!record) {
            if (status != kIOReturnSuccess) {
                set_error(ctx, "iAP2 SDP 服务查询未能启动，且没有缓存的 iAP2 服务记录", status);
            } else if (!events.sdpDone) {
                set_error(ctx, "iAP2 SDP 查询超时，且没有缓存的 iAP2 服务记录（请解锁 iPhone 并靠近 Mac）", 4);
            } else {
                set_error(ctx, "设备 SDP 列表中没有 iAP2 RFCOMM 服务 UUID，无法连接", events.sdpResult);
            }
            goto finish_connect;
        }

        on_main(^{ status = [record getRFCOMMChannelID:&channelId]; });
        if (status != kIOReturnSuccess || channelId == 0) {
            set_error(ctx, "iAP2 SDP 服务没有可用 RFCOMM channel ID", status);
            goto finish_connect;
        }
        on_main(^{
            IOBluetoothRFCOMMChannel *opened = nil;
            status = [device openRFCOMMChannelAsync:&opened withChannelID:channelId delegate:events];
            channel = opened;
        });
        if (status != kIOReturnSuccess) {
            set_error(ctx, "无法启动 RFCOMM 通道连接", status);
            goto finish_connect;
        }
        if (!wait_until(ctx, ^BOOL { return events.openDone; }, deadline)) {
            set_error(ctx, "RFCOMM 连接超时或被取消", 6);
            goto finish_connect;
        }
        on_main(^{ isOpen = [channel isOpen]; });
        if (events.openResult != kIOReturnSuccess || !isOpen) {
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
            // The worker owns the native socketpair end; the managed client owns the other.
            on_main(^{ mtu = (int)[channel getMTU]; });
            if (mtu < 1) mtu = 127;
            if (mtu > 65535) mtu = 65535;
            uint8_t buffer[4096];
            while (!events.closed && !should_abort(ctx)) {
                BOOL idle = YES;

                // Bluetooth -> managed Socket: drain data queued by the main-thread callback.
                @synchronized(events) {
                    NSUInteger rxCount = [events.pendingReceive length];
                    if (rxCount > 0) {
                        idle = NO;
                        ssize_t sent = send(ctx->fdNative, [events.pendingReceive bytes],
                                            MIN(rxCount, (NSUInteger)65536), 0);
                        if (sent > 0) {
                            [events.pendingReceive replaceBytesInRange:NSMakeRange(0, (NSUInteger)sent)
                                                             withBytes:NULL length:0];
                        } else if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                            events.closed = YES;
                        }
                    }
                }
                if (events.closed) break;

                // Managed Socket -> Bluetooth: chunks must not exceed RFCOMM MTU.
                ssize_t got = recv(ctx->fdNative, buffer, sizeof(buffer), MSG_DONTWAIT);
                if (got == 0) break;
                if (got < 0) {
                    if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) break;
                } else {
                    idle = NO;
                    size_t offset = 0;
                    while (offset < (size_t)got) {
                        UInt16 count = (UInt16)MIN((size_t)mtu, (size_t)got - offset);
                        uint8_t *chunk = buffer + offset;
                        on_main(^{ status = [channel writeSync:chunk length:count]; });
                        if (status != kIOReturnSuccess) {
                            events.closed = YES;
                            break;
                        }
                        offset += count;
                    }
                }
                if (idle) usleep(2000);
            }
        }

        if (channel || device) {
            on_main(^{
                if (channel) {
                    [channel setDelegate:nil];
                    [channel closeChannel];
                }
                if (device && !connected) [device closeConnection];
            });
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

// Runs the main-thread runloop that IOBluetooth delivers callbacks on. Must be called
// on the process main thread; returns -1 otherwise. Returns after iap2mac_stop_main_loop.
__attribute__((visibility("default")))
int iap2mac_run_main_loop(void) {
    if (!pthread_main_np()) return -1;
    g_main_loop_running = 1;
    while (!g_main_loop_stop) {
        @autoreleasepool {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.25, false);
        }
    }
    g_main_loop_running = 0;
    return 0;
}

__attribute__((visibility("default")))
int iap2mac_main_loop_running(void) {
    return g_main_loop_running;
}

__attribute__((visibility("default")))
void iap2mac_stop_main_loop(void) {
    g_main_loop_stop = 1;
    CFRunLoopStop(CFRunLoopGetMain());
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
        __block NSMutableArray *list = [NSMutableArray array];
        on_main(^{
            NSMutableSet *seen = [NSMutableSet set];
            IOBluetoothSDPUUID *uuid = [IOBluetoothSDPUUID uuidWithBytes:IAP2_UUID length:16];
            // pairedDevices may list the same device more than once; one entry per address.
            for (IOBluetoothDevice *device in [IOBluetoothDevice pairedDevices] ?: @[]) {
                NSString *address = device.addressString ?: @"";
                if ([address length] == 0 || [seen containsObject:address]) continue;
                [seen addObject:address];
                NSString *name = device.nameOrAddress ?: address;
                BOOL iap2 = uuid && [device getServiceRecordForUUID:uuid] != nil;
                [list addObject:@{
                    @"address": address,
                    @"name": name,
                    @"paired": @([device isPaired]),
                    @"connected": @([device isConnected]),
                    @"iap2": @(iap2)
                }];
            }
        });
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
        __block NSString *address = nil;
        on_main(^{
            address = [[IOBluetoothHostController defaultController] addressAsString];
        });
        return copy_utf8(address ?: @"", buffer, capacity);
    }
}

// Returns a *blocking* local stream-socket file descriptor owned by the caller.
// -1 means the remote RFCOMM connection was not established; error explains why.
__attribute__((visibility("default")))
int iap2mac_open(const char *address, int timeoutMs, char *error, int capacity) {
    if (error && capacity > 0) error[0] = 0;
    if (!g_main_loop_running) {
        copy_utf8(@"主线程 runloop 未运行，IOBluetooth 回调无法送达", error, capacity);
        return -1;
    }
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
