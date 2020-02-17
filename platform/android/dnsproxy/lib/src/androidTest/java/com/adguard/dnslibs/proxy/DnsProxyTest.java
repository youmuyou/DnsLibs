package com.adguard.dnslibs.proxy;

import android.Manifest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Instrumented test, which will execute on an Android device.
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class DnsProxyTest {
    static {
        DnsProxy.setLogLevel(DnsProxy.LogLevel.TRACE);
    }

    private static final Logger log = LoggerFactory.getLogger(DnsProxyTest.class);

    // In case of "permission denied", try uninstalling the test application from the device.
    @Rule
    public GrantPermissionRule rule = GrantPermissionRule.grant(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
    );

    @Test
    public void testProxyInit() {
        final DnsProxySettings defaultSettings = DnsProxySettings.getDefault();
        try (final DnsProxy proxy = new DnsProxy(defaultSettings)) {
            assertEquals(proxy.getSettings(), defaultSettings);
        }

        final DnsProxy proxy = new DnsProxy(defaultSettings);
        proxy.close();
        proxy.close();
        proxy.close();
        proxy.close(); // Check that multiple close() doesn't crash
    }

    @Test
    public void testHandleMessage() {
        try (final DnsProxy proxy = new DnsProxy(DnsProxySettings.getDefault())) {
            final byte[] request = new byte[64];
            ThreadLocalRandom.current().nextBytes(request);
            final byte[] response = proxy.handleMessage(request); // returns empty array on error
            assertNotNull(response);
            assertEquals(0, response.length);
        }
    }

    @Test
    public void testEventsMultithreaded() {
        final DnsProxySettings settings = DnsProxySettings.getDefault();
        final ListenerSettings tcp = new ListenerSettings();
        tcp.setAddress("::");
        tcp.setPort(12345);
        tcp.setProtocol(ListenerSettings.Protocol.TCP);
        tcp.setPersistent(true);
        tcp.setIdleTimeoutMs(5000);
        settings.getListeners().add(tcp);

        final ListenerSettings udp = new ListenerSettings();
        udp.setAddress("::");
        udp.setPort(12345);
        udp.setProtocol(ListenerSettings.Protocol.UDP);
        settings.getListeners().add(udp);

        final List<DnsRequestProcessedEvent> eventList =
                Collections.synchronizedList(new ArrayList<DnsRequestProcessedEvent>());

        final DnsProxyEvents events = new DnsProxyEvents() {
            @Override
            public void onRequestProcessed(DnsRequestProcessedEvent event) {
                log.info("DNS request processed event: {}", event.toString());
                eventList.add(event);
            }
        };

        try (final DnsProxy proxy = new DnsProxy(settings, events)) {
            final List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 10; ++i) {
                final Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final byte[] request = new byte[64];
                        ThreadLocalRandom.current().nextBytes(request);

                        final byte[] response = proxy.handleMessage(request);
                        assertNotNull(response);
                        assertEquals(0, response.length);
                    }
                });
                t.start();
                threads.add(t);
            }

            for (final Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException ignored) {
                }
            }

            assertEquals(threads.size(), eventList.size());
            for (final DnsRequestProcessedEvent event : eventList) {
                assertNotNull(event.getError());
                assertNotNull(event.getAnswer());
                assertNotNull(event.getDomain());
                assertNotNull(event.getUpstreamAddr());
                assertNotNull(event.getFilterListIds());
                assertNotNull(event.getRules());
                assertNotNull(event.getType());
                assertFalse(event.getError().isEmpty()); // 64 random bytes should result in parsing error...
            }
        }
    }

    @Test
    public void testListeners() {
        final DnsProxySettings settings = DnsProxySettings.getDefault();
        final ListenerSettings tcp = new ListenerSettings();
        tcp.setAddress("::");
        tcp.setPort(12345);
        tcp.setProtocol(ListenerSettings.Protocol.TCP);
        tcp.setPersistent(true);
        tcp.setIdleTimeoutMs(5000);
        settings.getListeners().add(tcp);

        final ListenerSettings udp = new ListenerSettings();
        udp.setAddress("::");
        udp.setPort(12345);
        udp.setProtocol(ListenerSettings.Protocol.UDP);
        settings.getListeners().add(udp);

        try (final DnsProxy proxy = new DnsProxy(settings)) {
            assertEquals(proxy.getSettings(), settings);
        }
    }

    @Test
    public void testSettingsMarshalling() {
        final DnsProxySettings settings = DnsProxySettings.getDefault();

        settings.setBlockedResponseTtlSecs(1234);

        settings.setIpv6Available(ThreadLocalRandom.current().nextBoolean());
        settings.setBlockIpv6(ThreadLocalRandom.current().nextBoolean());

// FIXME can't do these anymore: wrong filter path == proxy won't initialize
//        settings.getFilterParams().put(1, "/Й/И/Л"); // Test CESU-8 encoding
//        settings.getFilterParams().put(-2, "/A/B/C/D/Ы/Щ");
//        settings.getFilterParams().put(Integer.MAX_VALUE, "/A/B/Я/З/Ъ");
//        settings.getFilterParams().put(Integer.MIN_VALUE, "a/b\u0000c/d");

        final ListenerSettings tcp = new ListenerSettings();
        tcp.setAddress("::");
        tcp.setPort(12345);
        tcp.setProtocol(ListenerSettings.Protocol.TCP);
        tcp.setPersistent(true);
        tcp.setIdleTimeoutMs(5000);
        settings.getListeners().add(tcp);

        final ListenerSettings udp = new ListenerSettings();
        udp.setAddress("::");
        udp.setPort(12345);
        udp.setProtocol(ListenerSettings.Protocol.UDP);
        settings.getListeners().add(udp);

        final UpstreamSettings dot = new UpstreamSettings();
        dot.setAddress("tls://dns.adguard.com");
        dot.getBootstrap().add("8.8.8.8");
        dot.setServerIp(new byte[]{8, 8, 8, 8});
        dot.setTimeoutMs(10000);
        settings.getUpstreams().add(dot);

        final Dns64Settings dns64 = new Dns64Settings();
        dns64.setUpstreams(Collections.singletonList(dot));
        dns64.setMaxTries(1234);
        dns64.setWaitTimeMs(3456);
        settings.setDns64(dns64);

        settings.setListeners(settings.getListeners());
        settings.setUpstreams(settings.getUpstreams());
        settings.setFilterParams(settings.getFilterParams());
        settings.getUpstreams().get(0).setBootstrap(Collections.singletonList("1.1.1.1"));

        settings.setBlockingMode(DnsProxySettings.BlockingMode.CUSTOM_ADDRESS);
        settings.setCustomBlockingIpv4("4.3.2.1");
        settings.setCustomBlockingIpv6("43::21");

        settings.setDnsCacheSize(42);

        UpstreamSettings fallbackUpstream = new UpstreamSettings();
        fallbackUpstream.setAddress("https://fall.back/up/stream");
        fallbackUpstream.setBootstrap(Collections.singletonList("1.1.1.1"));
        fallbackUpstream.setServerIp(new byte[]{8, 8, 8, 8});
        fallbackUpstream.setTimeoutMs(4200);
        settings.getFallbacks().add(fallbackUpstream);

        try (final DnsProxy proxy = new DnsProxy(settings)) {
            assertEquals(settings, proxy.getSettings());
            assertFalse(proxy.getSettings().getListeners().isEmpty());
            assertFalse(proxy.getSettings().getUpstreams().isEmpty());
            assertFalse(proxy.getSettings().getUpstreams().get(0).getBootstrap().isEmpty());
        }

        settings.setCustomBlockingIpv4(null);
        settings.setCustomBlockingIpv6(null);

        try (final DnsProxy proxy = new DnsProxy(settings)) {
            assertTrue(proxy.getSettings().getCustomBlockingIpv4().isEmpty());
            assertTrue(proxy.getSettings().getCustomBlockingIpv6().isEmpty());
            settings.setCustomBlockingIpv4("");
            settings.setCustomBlockingIpv6("");
            assertEquals(settings, proxy.getSettings());
        }
    }

    private void testCertificateVerification(String upstreamAddr) {
        final UpstreamSettings us = new UpstreamSettings();
        us.setAddress(upstreamAddr);
        us.getBootstrap().add("8.8.8.8");
        us.setTimeoutMs(10000);
        final DnsProxySettings settings = DnsProxySettings.getDefault();
        settings.getUpstreams().clear();
        settings.getUpstreams().add(us);
        settings.setIpv6Available(false); // DoT times out trying to reach dns.adguard.com over IPv6

        final DnsProxyEvents events = new DnsProxyEvents() {
            @Override
            public void onRequestProcessed(DnsRequestProcessedEvent event) {
                log.info("DNS request processed event: {}", event.toString());
            }
        };

        try (final DnsProxy proxy = new DnsProxy(settings, events)) {
            assertEquals(settings, proxy.getSettings());

            final Message req = Message.newQuery(Record.newRecord(Name.fromString("google.com."), Type.A, DClass.IN));
            final Message res = new Message(proxy.handleMessage(req.toWire()));

            assertEquals(Rcode.NOERROR, res.getRcode());
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test
    public void testDoT() {
        testCertificateVerification("tls://dns.adguard.com");
        testCertificateVerification("tls://1.1.1.1");
        testCertificateVerification("tls://one.one.one.one");
    }

    @Test
    public void testDoH() {
        testCertificateVerification("https://dns.google/dns-query");
        testCertificateVerification("https://dns.cloudflare.com/dns-query");
    }

    @Test
    public void testCheckRule() {
        assertFalse(DnsProxy.isValidRule("||||example"));
        assertTrue(DnsProxy.isValidRule("||example"));
    }

    @Test
    public void testParseDNSStamp() {
        Map<String, DnsStamp> testParams = new LinkedHashMap<String, DnsStamp>() {{
            // Plain
            put("sdns://AAcAAAAAAAAABzguOC44Ljg",
                new DnsStamp(DnsStamp.ProtoType.PLAIN, "8.8.8.8:53", "", ""));
            // AdGuard DNS (DNSCrypt)
            put("sdns://AQIAAAAAAAAAFDE3Ni4xMDMuMTMwLjEzMDo1NDQzINErR_JS3PLCu_iZEIbq95zkSV2LFsigxDIuUso_OQhzIjIuZG5zY3J5cHQuZGVmYXVsdC5uczEuYWRndWFyZC5jb20",
                new DnsStamp(DnsStamp.ProtoType.DNSCRYPT, "176.103.130.130:5443", "2.dnscrypt.default.ns1.adguard.com", ""));
            // DoH
            put("sdns://AgcAAAAAAAAACTEyNy4wLjAuMSDDhGvyS56TymQnTA7GfB7MXgJP_KzS10AZNQ6B_lRq5AtleGFtcGxlLmNvbQovZG5zLXF1ZXJ5",
                new DnsStamp(DnsStamp.ProtoType.DOH, "127.0.0.1:443", "example.com", "/dns-query"));
            // DoT
            put("sdns://AwcAAAAAAAAACTEyNy4wLjAuMSDDhGvyS56TymQnTA7GfB7MXgJP_KzS10AZNQ6B_lRq5AtleGFtcGxlLmNvbQ",
                new DnsStamp(DnsStamp.ProtoType.TLS, "127.0.0.1:853", "example.com", ""));
            // Plain (IPv6)
            put("sdns://AAcAAAAAAAAAGltmZTgwOjo2ZDZkOmY3MmM6M2FkOjYwYjhd",
                new DnsStamp(DnsStamp.ProtoType.PLAIN, "[fe80::6d6d:f72c:3ad:60b8]:53", "", ""));
        }};

        for (Map.Entry<String, DnsStamp> entry : testParams.entrySet()) {
            DnsStamp validStamp = entry.getValue();
            try {
                DnsStamp dnsStamp = DnsProxy.parseDnsStamp(entry.getKey());
                assertEquals(dnsStamp, validStamp);
            } catch (Exception e) {
                fail(e.toString());
            }
        }
        try {
            DnsStamp dnsStamp = DnsProxy.parseDnsStamp("");
        } catch (Exception e) {
            assertFalse(e.toString().isEmpty());
        }
    }

    @Test
    public void testTestUpstream() {
        final long timeout = 500; // ms
        IllegalArgumentException e0 = null;
        try {
            DnsProxy.testUpstream(new UpstreamSettings("123.12.32.1:1493", new ArrayList<String>(), timeout, null));
        } catch (IllegalArgumentException e) {
            e0 = e;
        }
        assertNotNull(e0);
        try {
            DnsProxy.testUpstream(new UpstreamSettings("8.8.8.8:53", new ArrayList<String>(), 10 * timeout, null));
        } catch (IllegalArgumentException e) {
            fail(e.toString());
        }
        try {
            ArrayList<String> bootstrap = new ArrayList<>();
            bootstrap.add("1.2.3.4");
            bootstrap.add("8.8.8.8");
            DnsProxy.testUpstream(new UpstreamSettings("tls://dns.adguard.com", bootstrap, 10 * timeout, null));
        } catch (IllegalArgumentException e) {
            fail(e.toString());
        }
    }
}
