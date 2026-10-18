package fyi.slot.android;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Local IPv4 addresses a computer can open, Wi-Fi first, wired next and VPN last. */
final class NetworkAddresses {
    private NetworkAddresses() { }

    record Candidate(String iface, String address) {}

    static List<String> urls(int port) {
        List<Candidate> found = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                        found.add(new Candidate(network.getName(), address.getHostAddress()));
                }
            }
        } catch (SocketException | NullPointerException error) { return List.of(); }
        return order(found, port);
    }

    static List<String> order(List<Candidate> found, int port) {
        List<Candidate> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt((Candidate candidate) -> rank(candidate.iface()))
                .thenComparing(Candidate::iface).thenComparing(Candidate::address));
        List<String> urls = new ArrayList<>();
        for (Candidate candidate : sorted) {
            String url = "http://" + candidate.address() + ":" + port;
            if (!urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    static int rank(String iface) {
        String name = iface.toLowerCase(Locale.ROOT);
        if (name.startsWith("wlan") || name.startsWith("wifi")) return 0;
        if (name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis")) return 1;
        if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") || name.startsWith("ipsec") || name.startsWith("wg")) return 3;
        return 2;
    }
}
