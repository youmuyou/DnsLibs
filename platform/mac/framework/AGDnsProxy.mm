#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <assert.h>

#import "AGDnsProxy.h"

#include <ag_logger.h>
#include <dnsproxy.h>
#include <spdlog/sinks/base_sink.h>


class nslog_sink : public spdlog::sinks::base_sink<std::mutex> {
public:
    static ag::logger create(const std::string &logger_name) {
        return spdlog::default_factory::template create<nslog_sink>(logger_name);
    }

private:
    void sink_it_(const spdlog::details::log_msg &msg) override {
        spdlog::memory_buf_t formatted;
        this->formatter_->format(msg, formatted);
        NSLog(@"%.*s", (int)formatted.size(), formatted.data());
    }

    void flush_() override {}
};


#pragma pack(push,1)
struct iphdr {
#if BYTE_ORDER == LITTLE_ENDIAN
    u_int   ip_hl:4;        /* header length */
    u_int   ip_v:4;         /* version */
#endif
#if BYTE_ORDER == BIG_ENDIAN
    u_int   ip_v:4;         /* version */
    u_int   ip_hl:4;        /* header length */
#endif
    uint8_t ip_tos;         /* type of service */
    uint16_t    ip_len;         /* total length */
    uint16_t    ip_id;          /* identification */
    uint16_t    ip_off;         /* fragment offset field */
    uint8_t ip_ttl;         /* time to live */
    uint8_t ip_p;           /* protocol */
    uint16_t    ip_sum;         /* checksum */
    struct  in_addr ip_src;
    struct  in_addr ip_dst; /* source and dest address */
};

struct udphdr {
    uint16_t    uh_sport;       /* source port */
    uint16_t    uh_dport;       /* destination port */
    uint16_t    uh_ulen;        /* udp length */
    uint16_t    uh_sum;         /* udp checksum */
};
#pragma pack(pop)

static uint16_t ip_checksum(const void *ip_header, uint32_t header_length) {
    uint32_t checksum = 0;
    const uint16_t *data = (uint16_t *)ip_header;
    while (header_length > 1) {
        checksum += *data;
        data++;
        header_length -= 2;
    }
    if (header_length > 0) {
        checksum += *(uint8_t*)data;
    }

    checksum = (checksum >> 16) + (checksum & 0xffff);
    checksum = (checksum >> 16) + (checksum & 0xffff);

    return ~checksum & 0xffff;
}

static uint16_t checksum(const void *buf, size_t len, uint32_t sum) {
    uint32_t i;
    for (i = 0; i < (len & ~1U); i += 2) {
        sum += (uint16_t)ntohs(*((uint16_t *)((uint8_t*)buf + i)));
        if (sum > 0xFFFF) {
            sum -= 0xFFFF;
        }
    }

    if (i < len) {
        sum += ((uint8_t*)buf)[i] << 8;
        if (sum > 0xFFFF) {
            sum -= 0xFFFF;
        }
    }

    return sum;
}

static uint16_t udp_checksum(const struct iphdr *ip_header, const struct udphdr *udp_header,
        const void *buf, size_t len) {
    uint32_t sum = ip_header->ip_p + (uint32_t)ntohs(udp_header->uh_ulen);
    sum = checksum(&ip_header->ip_src, 2 * sizeof(ip_header->ip_src), sum);
    sum = checksum(buf, len, sum);
    sum = checksum(udp_header, sizeof(*udp_header), sum);
    sum = ~sum & 0xFFFF;
    return htons(sum);
}

static NSData *create_response_packet(const struct iphdr *ip_header, const struct udphdr *udp_header,
        const std::vector<uint8_t> &payload) {
    struct udphdr reverse_udp_header = {};
    reverse_udp_header.uh_sport = udp_header->uh_dport;
    reverse_udp_header.uh_dport = udp_header->uh_sport;
    reverse_udp_header.uh_ulen = htons(sizeof(reverse_udp_header) + payload.size());

    struct iphdr reverse_ip_header = {};
    reverse_ip_header.ip_v = ip_header->ip_v;
    reverse_ip_header.ip_hl = 5; // ip header without options
    reverse_ip_header.ip_tos = ip_header->ip_tos;
    reverse_ip_header.ip_len = htons(ntohs(reverse_udp_header.uh_ulen) + reverse_ip_header.ip_hl * 4);
    reverse_ip_header.ip_id = ip_header->ip_id;
    reverse_ip_header.ip_ttl = ip_header->ip_ttl;
    reverse_ip_header.ip_p = ip_header->ip_p;
    reverse_ip_header.ip_src = ip_header->ip_dst;
    reverse_ip_header.ip_dst = ip_header->ip_src;

    reverse_ip_header.ip_sum = ip_checksum(&reverse_ip_header, sizeof(reverse_ip_header));
    reverse_udp_header.uh_sum = udp_checksum(&reverse_ip_header, &reverse_udp_header,
        payload.data(), payload.size());

    NSMutableData *reverse_packet = [[NSMutableData alloc] initWithCapacity: reverse_ip_header.ip_len];
    [reverse_packet appendBytes: &reverse_ip_header length: sizeof(reverse_ip_header)];
    [reverse_packet appendBytes: &reverse_udp_header length: sizeof(reverse_udp_header)];
    [reverse_packet appendBytes: payload.data() length: payload.size()];

    return reverse_packet;
}


extern "C" void AGSetLogLevel(AGLogLevel level) {
    ag::set_default_log_level((ag::log_level)level);
}

@implementation AGDnsUpstream
- (instancetype) initWithNative: (const ag::dnsproxy_settings::upstream_settings *) settings
{
    [super init];
    _address = [NSString stringWithUTF8String: settings->dns_server.c_str()];
    NSMutableArray<NSString *> *bootstrap =
        [[NSMutableArray alloc] initWithCapacity: settings->options.bootstrap.size()];
    for (const std::string &server : settings->options.bootstrap) {
        [bootstrap addObject: [NSString stringWithUTF8String: server.c_str()]];
    }
    _bootstrap = bootstrap;
    _timeout = settings->options.timeout.count();
    return self;
}

- (instancetype) init: (NSString *) address
        bootstrap: (NSArray<NSString *> *) bootstrap
        timeout: (NSInteger) timeout
{
    [super init];
    _address = address;
    _bootstrap = bootstrap;
    _timeout = timeout;
    return self;
}
@end


@implementation AGDnsProxyConfig

- (instancetype) initWithNative: (const ag::dnsproxy_settings *) settings
{
    [super init];
    NSMutableArray<AGDnsUpstream *> *upstreams =
        [[NSMutableArray alloc] initWithCapacity: settings->upstreams.size()];
    for (const ag::dnsproxy_settings::upstream_settings &us : settings->upstreams) {
        [upstreams addObject: [[AGDnsUpstream alloc] initWithNative: &us]];
    }
    _upstreams = upstreams;
    _filters = nil;
    _blockedResponseTtl = settings->blocked_response_ttl;
    return self;
}

- (instancetype) init: (NSArray<AGDnsUpstream *> *) upstreams
        filters: (NSDictionary<NSNumber *,NSString *> *) filters
        blockedResponseTtl: (NSInteger) blockedResponseTtl
{
    const ag::dnsproxy_settings &defaultSettings = ag::dnsproxy_settings::get_default();
    [self initWithNative: &defaultSettings];
    if (upstreams != nil) {
        _upstreams = upstreams;
    }
    _filters = filters;
    if (blockedResponseTtl != 0) {
        _blockedResponseTtl = blockedResponseTtl;
    }
    return self;
}

+ (instancetype) getDefault
{
    const ag::dnsproxy_settings &defaultSettings = ag::dnsproxy_settings::get_default();
    return [[AGDnsProxyConfig alloc] initWithNative: &defaultSettings];
}
@end


@implementation AGDnsProxy {
    ag::dnsproxy proxy;
    ag::logger log;
}

- (void) dealloc
{
    self->proxy.deinit();
    [super dealloc];
}

- (instancetype) init: (AGDnsProxyConfig *) config
{
    ag::set_logger_factory_callback(nslog_sink::create);
    self->log = ag::create_logger("AGDnsProxy");

    infolog(self->log, "Initializing dns proxy...");

    ag::dnsproxy_settings settings = ag::dnsproxy_settings::get_default();
    if (config.upstreams != nil) {
        settings.upstreams.reserve([config.upstreams count]);
        for (AGDnsUpstream *upstream in config.upstreams) {
            std::list<std::string> bootstrap;
            for (NSString *server in upstream.bootstrap) {
                bootstrap.emplace_back([server UTF8String]);
            }
            settings.upstreams.emplace_back(
                ag::dnsproxy_settings::upstream_settings{ [upstream.address UTF8String],
                    { std::move(bootstrap), std::chrono::milliseconds(upstream.timeout) } });
        }
    }

    settings.blocked_response_ttl = config.blockedResponseTtl;

    if (config.filters != nil) {
        settings.filter_params.filters.reserve([config.filters count]);
        for (NSNumber *key in config.filters) {
            const char *filterPath = [[config.filters objectForKey: key] UTF8String];
            dbglog(self->log, "filter id={} path={}", [key intValue], filterPath);
            settings.filter_params.filters.emplace_back(
                ag::dnsfilter::filter_params{ (uint32_t)[key intValue], filterPath });
        }
    }

    if (!self->proxy.init(std::move(settings))) {
        errlog(self->log, "Failed to initialize filtering module");
        return nil;
    }

    infolog(self->log, "Dns proxy initialized");

    return self;
}

- (NSData *) handlePacket: (NSData *) packet
{
    struct iphdr *ip_header = (struct iphdr *)packet.bytes;
    // @todo: handle tcp packets also
    if (ip_header->ip_p != IPPROTO_UDP) {
        return nil;
    }

    NSInteger ip_header_length = ip_header->ip_hl * 4;
    struct udphdr *udp_header = (struct udphdr *)((Byte *)packet.bytes + ip_header_length);
    NSInteger udp_header_length = ip_header_length + sizeof(struct udphdr);
    dbglog(self->log, "{}:{} -> {}:{}"
        , inet_ntoa(ip_header->ip_src), ntohs(udp_header->uh_sport)
        , inet_ntoa(ip_header->ip_dst), ntohs(udp_header->uh_dport));

    NSData *payload = [NSData dataWithBytes: ((Byte *)packet.bytes + udp_header_length)
        length: (packet.length - udp_header_length)];

    std::vector<uint8_t> response = self->proxy.handle_message({(uint8_t*)[payload bytes], [payload length]});
    return create_response_packet(ip_header, udp_header, response);
}
@end