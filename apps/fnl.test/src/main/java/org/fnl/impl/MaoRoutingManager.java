/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fnl.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.apache.felix.scr.annotations.*;
import org.fnl.intf.MaoRoutingService;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpPrefix;
import org.onosproject.common.DefaultTopology;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.incubator.net.PortStatisticsService;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.PathIntent;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.provider.ProviderId;
import org.onosproject.net.topology.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Skeletal ONOS application component.
 *
 * @author Mao
 */
@Component(immediate = true)
@Service
public class MaoRoutingManager implements MaoRoutingService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private PortStatisticsService portStatisticsService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private TopologyService topologyService;

    private ApplicationId appId;


    @Activate
    protected void activate() {
        log.info("Started");

        packetService.addProcessor(packetProcessor, PacketProcessor.director(0));

        appId = coreService.registerApplication("org.fnl.test");

        packetService.requestPackets(DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE,
                appId);

        intentMap = new HashMap<>();
        intentService.getIntents().forEach(intent -> {
            intentService.withdraw(intent);
            intentService.purge(intent);
        });

//        intentService.getIntents().forEach(intent -> {
//            intentService.withdraw(intent);
//            intentService.purge(intent);
//        });
//        while(true) {
//            Iterable<Device> devices = deviceService.getDevices();
//            for (Device d : devices) {
//                List<Port> ports = deviceService.getPorts(d.id());
//                for (Port p : ports) {
//                    PortNumber portNumber = p.number();
//                    long portSpeed = p.portSpeed();
//                    int a = 0;
//                }
//            }
//
//
//            Iterable<Link> links = linkService.getLinks();
//            for (Link l : links) {
//                Load srcLoad = portStatisticsService.load(l.src());
//                Load dstLoad = portStatisticsService.load(l.dst());
//                int a = 0;
//            }
//        }
    }

    @Deactivate
    protected void deactivate() {
        log.info("Stopped");
        packetService.removeProcessor(packetProcessor);
        packetService.cancelPackets(DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE,
                appId);

        intentMap.values().forEach(intent -> {
            intentService.withdraw(intent);
            intentService.purge(intent);
        });
        intentMap.clear();
    }


    private Map<Set<Criterion>, Intent> intentMap;

    private InternalPacketProcessor packetProcessor = new InternalPacketProcessor();

    private class InternalPacketProcessor implements PacketProcessor {

        //        private volatile int count = 0;
//        private Integer lock = 0;
        private Integer mapLock = 0;


        @Override
        public void process(PacketContext context) {

//            synchronized (lock) {
//                if (context.isHandled() || count > 0) {
            if (context.isHandled()) {
                return;
            }
//            }

            Ethernet pkt = context.inPacket().parsed();
            if (pkt.getEtherType() == Ethernet.TYPE_IPV4) {

                HostId srcHostId = HostId.hostId(pkt.getSourceMAC());
                HostId dstHostId = HostId.hostId(pkt.getDestinationMAC());

                Set<Path> paths = getLoadBalancePaths(srcHostId, dstHostId);
                if (paths.isEmpty()) {
                    log.warn("paths is Empty !!! no Path is available");
                    context.block();
                    return;
                }

                IPv4 ipPkt = (IPv4) pkt.getPayload();
                TrafficSelector selector = DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(IpPrefix.valueOf(ipPkt.getSourceAddress(), 32))
                        .matchIPDst(IpPrefix.valueOf(ipPkt.getDestinationAddress(), 32))
                        .build();

                boolean isContain;
                synchronized (mapLock) {
                    isContain = intentMap.containsKey(selector.criteria());
                }
                if (isContain) {
                    context.block();
                    return;
                }


                Path result = paths.iterator().next();
                log.info("\n------ Mao Path Info ------\nSrc:{}, Dst:{}\n{}",
                        IpPrefix.valueOf(ipPkt.getSourceAddress(), 32).toString(),
                        IpPrefix.valueOf(ipPkt.getDestinationAddress(), 32),
                        result.links().toString().replace("Default", "\n"));

                PathIntent pathIntent = PathIntent.builder()
                        .path(result)
                        .appId(appId)
                        .priority(65432)
                        .selector(selector)
                        .treatment(DefaultTrafficTreatment.emptyTreatment())
                        .build();
                intentService.submit(pathIntent);
                synchronized (mapLock) {
                    intentMap.put(selector.criteria(), pathIntent);
                }
//                synchronized (lock) {
//                    count++;
//                }

                context.block();
            }
        }
    }


    //=================== Start =====================

    ProviderId routeProviderId = new ProviderId("FNL", "Mao");

    /**
     * Compatible Entry of routing function.
     *
     * @param src
     * @param dst
     * @return empty Set if
     * 1. no path found
     * 2. given srcHost or dstHost is not discovered by ONOS
     * 3. given srcDevice and dstDevice are identical one.
     */
    private Set<Path> getLoadBalancePaths(ElementId src, ElementId dst) {
        Topology currentTopo = topologyService.currentTopology();
        return getLoadBalancePaths(currentTopo, src, dst);
    }

    /**
     * Core Entry of routing function.
     *
     * @param topo
     * @param src
     * @param dst
     * @return empty Set if
     * 1. no path found
     * 2. given srcHost or dstHost is not discovered by ONOS
     * 3. given srcDevice and dstDevice are identical one.
     */
    private Set<Path> getLoadBalancePaths(Topology topo, ElementId src, ElementId dst) {

        if (src instanceof DeviceId && dst instanceof DeviceId) {

            // no need to create edge link.
            // --- Three Step by Mao. ---

            Set<List<TopologyEdge>> allRoutes = findAllRoutes(topo, (DeviceId) src, (DeviceId) dst);

            Set<Path> allPaths = calculateRoutesCost(allRoutes);

            Path linkPath = selectRoute(allPaths);


            //use Set to be compatible with ONOS API
            return linkPath != null ? ImmutableSet.of(linkPath) : ImmutableSet.of();

        } else if (src instanceof HostId && dst instanceof HostId) {


            Host srcHost = hostService.getHost((HostId) src);
            Host dstHost = hostService.getHost((HostId) dst);
            if (srcHost == null || dstHost == null) {
                log.warn("Generate whole path but found null, hostSrc:{}, hostDst:{}", srcHost, dstHost);
                return ImmutableSet.of();
            }
            EdgeLink srcLink = getEdgeLink(srcHost, true);
            EdgeLink dstLink = getEdgeLink(dstHost, false);


            // --- Four Step by Mao. ---

            Set<List<TopologyEdge>> allRoutes = findAllRoutes(topo, srcLink.dst().deviceId(), dstLink.src().deviceId());

            Set<Path> allPaths = calculateRoutesCost(allRoutes);

            Path linkPath = selectRoute(allPaths);

            Path wholePath = buildWholePath(srcLink, dstLink, linkPath);

            //use Set to be compatible with ONOS API
            return wholePath != null ? ImmutableSet.of(wholePath) : ImmutableSet.of();

        } else {
            //use Set to be compatible with ONOS API
            return ImmutableSet.of();
        }
    }

    /**
     * Generate EdgeLink which is between Host and Device.
     * Tool for getLoadBalancePaths().
     *
     * @param host
     * @param isIngress whether it is Ingress to Device or not.
     * @return
     */
    private EdgeLink getEdgeLink(Host host, boolean isIngress) {
        return new DefaultEdgeLink(routeProviderId, new ConnectPoint(host.id(), PortNumber.portNumber(0)),
                host.location(), isIngress);
    }

    //=================== Step One: Find routes =====================

    /**
     * Entry for find all Paths between Src and Dst.
     * By Mao.
     *
     * @param src  Src of Path.
     * @param dst  Dst of Path.
     * @param topo Topology, MUST be an Object of DefaultTopology now.
     */
    private Set<List<TopologyEdge>> findAllRoutes(Topology topo, DeviceId src, DeviceId dst) {
        if (!(topo instanceof DefaultTopology)) {
            log.error("topology is not the object of DefaultTopology.");
            return ImmutableSet.of();
        }

        Set<List<TopologyEdge>> graghResult = new HashSet<>();
        dfsFindAllRoutes(new DefaultTopologyVertex(src), new DefaultTopologyVertex(dst),
                new ArrayList<>(), new ArrayList<>(),
                ((DefaultTopology) topo).getGraph(), graghResult);

        return graghResult;
    }

    /**
     * Get all possible path between Src and Dst using DFS, by Mao.
     * DFS Core, Recursion Part.
     *
     * @param src          Source point per Recursion
     * @param dst          Final Objective
     * @param passedLink   dynamic, record passed links in real time
     * @param passedDevice dynamic, record entered devices in real time, to avoid loop
     * @param topoGraph    represent the whole world
     * @param result       Set of all Paths.
     * @return no use.
     */
    private void dfsFindAllRoutes(TopologyVertex src,
                                  TopologyVertex dst,
                                  List<TopologyEdge> passedLink,
                                  List<TopologyVertex> passedDevice,
                                  TopologyGraph topoGraph,
                                  Set<List<TopologyEdge>> result) {
        if (src.equals(dst))
            return;

        passedDevice.add(src);

        Set<TopologyEdge> egressSrc = topoGraph.getEdgesFrom(src);
        egressSrc.forEach(egress -> {
            TopologyVertex vertexDst = egress.dst();
            if (vertexDst.equals(dst)) {
                //Gain a Path
                passedLink.add(egress);
                result.add(ImmutableList.copyOf(passedLink.iterator()));
                passedLink.remove(egress);

            } else if (!passedDevice.contains(vertexDst)) {
                //DFS into
                passedLink.add(egress);
                dfsFindAllRoutes(vertexDst, dst, passedLink, passedDevice, topoGraph, result);
                passedLink.remove(egress);

            } else {
                //means - passedDevice.contains(vertexDst)
                //We hit a loop, NOT go into
            }
        });

        passedDevice.remove(src);
    }

    /**
     * Parse several TopologyEdge(s) to one Path.
     * Tool for findAllPaths.
     */
    private List<Link> parseEdgeToLink(List<TopologyEdge> edges) {
        List<Link> links = new ArrayList<>();
        edges.forEach(edge -> links.add(edge.link()));
        return links;
    }

    //=================== Step Two: Calculate Cost =====================

    private BandwidthLinkWeight bandwidthLinkWeightTool = new BandwidthLinkWeight();

    private Set<Path> calculateRoutesCost(Set<List<TopologyEdge>> routes) {

        Set<Path> paths = new HashSet<>();

        routes.forEach(route -> {
            double cost = maxLinkWeight(route);
            paths.add(parseEdgeToPath(route, cost));
        });

        return paths;
    }

    /**
     * A strategy to calculate the weight of one path.
     */
    private double maxLinkWeight(List<TopologyEdge> edges) {

        double weight = 0;
        for (TopologyEdge edge : edges) {
            double linkWeight = bandwidthLinkWeightTool.weight(edge);
            weight = weight < linkWeight ? linkWeight : weight;
        }
        return weight;
    }

    /**
     * Parse several TopologyEdge(s) to one Path.
     * Tool for calculateRoutesWeight().
     */
    private Path parseEdgeToPath(List<TopologyEdge> edges, double cost) {

        ArrayList links = new ArrayList();
        edges.forEach(edge -> links.add(edge.link()));

        return new DefaultPath(routeProviderId, links, cost);
    }

    //=================== Step Three: Select one route(Path) =====================

    private Path selectRoute(Set<Path> paths) {
        if (paths.size() < 1)
            return null;

        return getMinCostMinHopPath(new ArrayList(paths));
    }

    /**
     * A strategy to select one best Path.
     *
     * @param paths
     * @return whose max cost of all links is lowest.
     */
    private Path getMinCostPath(List<Path> paths) {
        Path result = paths.get(0);
        for (int i = 1, pathCount = paths.size(); i < pathCount; i++) {
            Path temp = paths.get(i);
            result = result.cost() > temp.cost() ? temp : result;
        }
        return result;
    }

    /**
     * A strategy to select one best Path.
     *
     * @param paths
     * @return whose count of all links is lowest.
     */
    private Path getMinHopPath(List<Path> paths) {
        Path result = paths.get(0);
        for (int i = 1, pathCount = paths.size(); i < pathCount; i++) {
            Path temp = paths.get(i);
            result = result.links().size() > temp.links().size() ? temp : result;
        }
        return result;
    }

    /**
     * An integrated strategy to select one best Path.
     *
     * @param paths
     * @return whose count of all links is lowest.
     */
    private Path getMinCostMinHopPath(List<Path> paths) {

        final double MEASURE_TOLERANCE = 0.05; // 0.05% represent 5M(10G), 12.5M(25G), 50M(100G)

        //Sort by Cost in order
        paths.sort((p1, p2) -> p1.cost() > p2.cost() ? 1 : (p1.cost() < p2.cost() ? -1 : 0));

        // get paths with similar lowest cost within MEASURE_TOLERANCE range.
        List<Path> minCostPaths = new ArrayList<>();
        Path result = paths.get(0);
        minCostPaths.add(result);
        for (int i = 1, pathCount = paths.size(); i < pathCount; i++) {
            Path temp = paths.get(i);
            if (temp.cost() - result.cost() < MEASURE_TOLERANCE) {
                minCostPaths.add(temp);
            }
        }

        result = getMinHopPath(minCostPaths);

        return result;
    }
    //=================== Step Four: Build whole Path, with edge links =====================

    /**
     * @param srcLink
     * @param dstLink
     * @param linkPath
     * @return At least, Path will include two edge links.
     */
    private Path buildWholePath(EdgeLink srcLink, EdgeLink dstLink, Path linkPath) {
        if (linkPath == null && !(srcLink.dst().deviceId().equals(dstLink.src().deviceId()))) {
            log.warn("no available Path is found!");
            return null;
        }

        return buildEdgeToEdgePath(srcLink, dstLink, linkPath);
    }

    /**
     * Produces a direct edge-to-edge path.
     *
     * @param srcLink
     * @param dstLink
     * @param linkPath
     * @return
     */
    private Path buildEdgeToEdgePath(EdgeLink srcLink, EdgeLink dstLink, Path linkPath) {

        List<Link> links = Lists.newArrayListWithCapacity(2);

        double cost = 0;

        //The cost of edge link is 0.
        links.add(srcLink);

        if (linkPath != null) {
            links.addAll(linkPath.links());
            cost += linkPath.cost();
        }

        links.add(dstLink);

        return new DefaultPath(routeProviderId, links, cost);
    }

    //=================== The End =====================


    /**
     * Tool for calculating weight value for each Link(TopologyEdge).
     *
     * @author Mao.
     */
    private class BandwidthLinkWeight implements LinkWeight {

        //        private static final double LINK_LINE_SPEED = 10000000000.0; // 10Gbps
        private static final double LINK_WEIGHT_DOWN = 100.0;
        private static final double LINK_WEIGHT_FULL = 100.0;

        //FIXME - Bata1: Here, assume the edge is the inter-demain link
        @Override
        public double weight(TopologyEdge edge) {

            if (edge.link().state() == Link.State.INACTIVE) {
                return LINK_WEIGHT_DOWN;
            }


            long linkLineSpeed = getLinkLineSpeed(edge.link());

            //FIXME - Bata1: Here, assume the value in the map is the rest bandwidth of inter-demain link
            long interLinkRestBandwidth = linkLineSpeed - getLinkLoadSpeed(edge.link());

            if (interLinkRestBandwidth <= 0) {
                return LINK_WEIGHT_FULL;
            }

            return 100 - interLinkRestBandwidth * 1.0 / linkLineSpeed * 100;//restBandwidthPersent
        }

        private long getLinkLineSpeed(Link link) {

            long srcSpeed = getPortLineSpeed(link.src());
            long dstSpeed = getPortLineSpeed(link.dst());

            return min(srcSpeed, dstSpeed);
        }

        private long getLinkLoadSpeed(Link link) {

            long srcSpeed = getPortLoadSpeed(link.src());
            long dstSpeed = getPortLoadSpeed(link.dst());

            return max(srcSpeed, dstSpeed);
        }

        /**
         * Unit: bps
         *
         * @param port
         * @return
         */
        private long getPortLoadSpeed(ConnectPoint port) {

            return portStatisticsService.load(port).rate() * 8;//data source: Bps

        }

        /**
         * Unit bps
         *
         * @param port
         * @return
         */
        private long getPortLineSpeed(ConnectPoint port) {

            assert port.elementId() instanceof DeviceId;
            return deviceService.getPort(port.deviceId(), port.port()).portSpeed() * 1000000;//data source: Mbps

        }

        private long max(long a, long b) {
            return a > b ? a : b;
        }

        private long min(long a, long b) {
            return a < b ? a : b;
        }

    }
}
