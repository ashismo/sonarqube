/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.application.cluster;

import com.google.common.collect.ImmutableSet;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Client;
import com.hazelcast.core.ClientListener;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.IAtomicReference;
import com.hazelcast.core.ILock;
import com.hazelcast.core.MapEvent;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.core.ReplicatedMap;
import com.hazelcast.nio.Address;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.NetworkUtils;
import org.sonar.application.AppStateListener;
import org.sonar.cluster.ClusterObjectKeys;
import org.sonar.cluster.localclient.HazelcastClient;
import org.sonar.process.MessageException;
import org.sonar.cluster.NodeType;
import org.sonar.process.ProcessId;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.sonar.application.cluster.ClusterProperties.HAZELCAST_CLUSTER_NAME;
import static org.sonar.cluster.ClusterObjectKeys.CLUSTER_NAME;
import static org.sonar.cluster.ClusterObjectKeys.HOSTNAME;
import static org.sonar.cluster.ClusterObjectKeys.IP_ADDRESSES;
import static org.sonar.cluster.ClusterObjectKeys.LEADER;
import static org.sonar.cluster.ClusterObjectKeys.LOCAL_MEMBER_UUIDS;
import static org.sonar.cluster.ClusterObjectKeys.NODE_NAME;
import static org.sonar.cluster.ClusterObjectKeys.NODE_TYPE;
import static org.sonar.cluster.ClusterObjectKeys.OPERATIONAL_PROCESSES;
import static org.sonar.cluster.ClusterObjectKeys.SONARQUBE_VERSION;

public class HazelcastCluster implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastCluster.class);

  private final List<AppStateListener> listeners = new ArrayList<>();
  private final ReplicatedMap<ClusterProcess, Boolean> operationalProcesses;
  private final String operationalProcessListenerUUID;
  private final String clientListenerUUID;
  private final String nodeDisconnectedListenerUUID;

  protected final HazelcastInstance hzInstance;

  private HazelcastCluster(Config hzConfig) {
    // Create the Hazelcast instance
    hzInstance = Hazelcast.newHazelcastInstance(hzConfig);

    // Get or create the replicated map
    operationalProcesses = hzInstance.getReplicatedMap(OPERATIONAL_PROCESSES);
    operationalProcessListenerUUID = operationalProcesses.addEntryListener(new OperationalProcessListener());
    clientListenerUUID = hzInstance.getClientService().addClientListener(new ConnectedClientListener());
    nodeDisconnectedListenerUUID = hzInstance.getCluster().addMembershipListener(new NodeDisconnectedListener());
  }

  String getLocalUUID() {
    return hzInstance.getLocalEndpoint().getUuid();
  }

  String getName() {
    return hzInstance.getConfig().getGroupConfig().getName();
  }

  List<String> getMembers() {
    return hzInstance.getCluster().getMembers().stream()
      .filter(m -> !m.localMember())
      .map(m -> format("%s (%s)", m.getStringAttribute(HOSTNAME), m.getStringAttribute(IP_ADDRESSES)))
      .collect(toList());
  }

  void addListener(AppStateListener listener) {
    listeners.add(listener);
  }

  boolean isOperational(ProcessId processId) {
    for (Map.Entry<ClusterProcess, Boolean> entry : operationalProcesses.entrySet()) {
      if (entry.getKey().getProcessId().equals(processId) && entry.getValue()) {
        return true;
      }
    }
    return false;
  }

  void setOperational(ProcessId processId) {
    operationalProcesses.put(new ClusterProcess(getLocalUUID(), processId), Boolean.TRUE);
  }

  boolean tryToLockWebLeader() {
    IAtomicReference<String> leader = hzInstance.getAtomicReference(LEADER);
    if (leader.get() == null) {
      ILock lock = hzInstance.getLock(LEADER);
      lock.lock();
      try {
        if (leader.get() == null) {
          leader.set(getLocalUUID());
          return true;
        } else {
          return false;
        }
      } finally {
        lock.unlock();
      }
    } else {
      return false;
    }
  }

  public void registerSonarQubeVersion(String sonarqubeVersion) {
    IAtomicReference<String> sqVersion = hzInstance.getAtomicReference(SONARQUBE_VERSION);
    if (sqVersion.get() == null) {
      ILock lock = hzInstance.getLock(SONARQUBE_VERSION);
      lock.lock();
      try {
        if (sqVersion.get() == null) {
          sqVersion.set(sonarqubeVersion);
        }
      } finally {
        lock.unlock();
      }
    }

    String clusterVersion = sqVersion.get();
    if (!sqVersion.get().equals(sonarqubeVersion)) {
      throw new IllegalStateException(
        format("The local version %s is not the same as the cluster %s", sonarqubeVersion, clusterVersion));
    }
  }

  public String getSonarQubeVersion() {
    IAtomicReference<String> sqVersion = hzInstance.getAtomicReference(SONARQUBE_VERSION);
    return sqVersion.get();
  }

  public void registerClusterName(String nodeValue) {
    IAtomicReference<String> property = hzInstance.getAtomicReference(CLUSTER_NAME);
    if (property.get() == null) {
      ILock lock = hzInstance.getLock(CLUSTER_NAME);
      lock.lock();
      try {
        if (property.get() == null) {
          property.set(nodeValue);
        }
      } finally {
        lock.unlock();
      }
    }

    String clusterValue = property.get();
    if (!property.get().equals(nodeValue)) {
      throw new MessageException(
        format("This node has a cluster name [%s], which does not match [%s] from the cluster", nodeValue, clusterValue));
    }
  }

  @Override
  public void close() {
    if (hzInstance != null) {
      try {
        // Removing listeners
        operationalProcesses.removeEntryListener(operationalProcessListenerUUID);
        hzInstance.getClientService().removeClientListener(clientListenerUUID);
        hzInstance.getCluster().removeMembershipListener(nodeDisconnectedListenerUUID);

        // Removing the operationalProcess from the replicated map
        operationalProcesses.keySet().forEach(
          clusterNodeProcess -> {
            if (clusterNodeProcess.getNodeUuid().equals(getLocalUUID())) {
              operationalProcesses.remove(clusterNodeProcess);
            }
          });

        // Shutdown Hazelcast properly
        hzInstance.shutdown();
      } catch (HazelcastInstanceNotActiveException e) {
        // hazelcastCluster may be already closed by the shutdown hook
        LOGGER.debug("Unable to close Hazelcast cluster", e);
      }
    }
  }

  public static HazelcastCluster create(ClusterProperties clusterProperties) {
    Config hzConfig = new Config();
    hzConfig.getGroupConfig().setName(HAZELCAST_CLUSTER_NAME);

    // Configure the network instance
    NetworkConfig netConfig = hzConfig.getNetworkConfig();
    netConfig
      .setPort(clusterProperties.getPort())
      .setReuseAddress(true);

    if (!clusterProperties.getNetworkInterfaces().isEmpty()) {
      netConfig.getInterfaces()
        .setEnabled(true)
        .setInterfaces(clusterProperties.getNetworkInterfaces());
    }

    // Only allowing TCP/IP configuration
    JoinConfig joinConfig = netConfig.getJoin();
    joinConfig.getAwsConfig().setEnabled(false);
    joinConfig.getMulticastConfig().setEnabled(false);
    joinConfig.getTcpIpConfig().setEnabled(true);
    joinConfig.getTcpIpConfig().setMembers(clusterProperties.getHosts());

    // Tweak HazelCast configuration
    hzConfig
      // Increase the number of tries
      .setProperty("hazelcast.tcp.join.port.try.count", "10")
      // Don't bind on all interfaces
      .setProperty("hazelcast.socket.bind.any", "false")
      // Don't phone home
      .setProperty("hazelcast.phone.home.enabled", "false")
      // Use slf4j for logging
      .setProperty("hazelcast.logging.type", "slf4j");

    // Trying to resolve the hostname
    hzConfig.getMemberAttributeConfig()
      .setStringAttribute(NODE_NAME, clusterProperties.getNodeName());
    hzConfig.getMemberAttributeConfig()
      .setStringAttribute(HOSTNAME, NetworkUtils.INSTANCE.getHostname());
    hzConfig.getMemberAttributeConfig()
      .setStringAttribute(IP_ADDRESSES, NetworkUtils.INSTANCE.getIPAddresses());
    hzConfig.getMemberAttributeConfig()
      .setStringAttribute(NODE_TYPE, clusterProperties.getNodeType().getValue());

    // We are not using the partition group of Hazelcast, so disabling it
    hzConfig.getPartitionGroupConfig().setEnabled(false);
    return new HazelcastCluster(hzConfig);
  }

  Optional<String> getLeaderHostName() {
    String leaderId = (String) hzInstance.getAtomicReference(LEADER).get();
    if (leaderId != null) {
      Optional<Member> leader = hzInstance.getCluster().getMembers().stream().filter(m -> m.getUuid().equals(leaderId)).findFirst();
      if (leader.isPresent()) {
        return Optional.of(
          format("%s (%s)", leader.get().getStringAttribute(HOSTNAME), leader.get().getStringAttribute(IP_ADDRESSES)));
      }
    }
    return Optional.empty();
  }

  String getLocalEndPoint() {
    Address localAddress = hzInstance.getCluster().getLocalMember().getAddress();
    return format("%s:%d", localAddress.getHost(), localAddress.getPort());
  }

  public HazelcastClient getHazelcastClient() {
    return new HazelcastInstanceClient(hzInstance);
  }

  private static class HazelcastInstanceClient implements HazelcastClient {
    private final HazelcastInstance hzInstance;

    private HazelcastInstanceClient(HazelcastInstance hzInstance) {
      this.hzInstance = hzInstance;
    }

    @Override
    public <E> Set<E> getSet(String s) {
      return hzInstance.getSet(s);
    }

    @Override
    public <E> List<E> getList(String s) {
      return hzInstance.getList(s);
    }

    @Override
    public <K, V> Map<K, V> getMap(String s) {
      return hzInstance.getMap(s);
    }

    @Override
    public <K, V> Map<K, V> getReplicatedMap(String s) {
      return hzInstance.getReplicatedMap(s);
    }

    @Override
    public String getUUID() {
      return hzInstance.getLocalEndpoint().getUuid();
    }

    @Override
    public Set<String> getMemberUuids() {
      ImmutableSet.Builder<String> builder = ImmutableSet.builder();
      builder.addAll(hzInstance.getSet(ClusterObjectKeys.LOCAL_MEMBER_UUIDS));
      hzInstance.getCluster().getMembers().stream().map(Member::getUuid).forEach(builder::add);
      return builder.build();
    }

    @Override
    public Lock getLock(String s) {
      return hzInstance.getLock(s);
    }

    @Override
    public long getClusterTime() {
      return hzInstance.getCluster().getClusterTime();
    }
  }

  private class OperationalProcessListener implements EntryListener<ClusterProcess, Boolean> {
    @Override
    public void entryAdded(EntryEvent<ClusterProcess, Boolean> event) {
      if (event.getValue()) {
        listeners.forEach(appStateListener -> appStateListener.onAppStateOperational(event.getKey().getProcessId()));
      }
    }

    @Override
    public void entryRemoved(EntryEvent<ClusterProcess, Boolean> event) {
      // Ignore it
    }

    @Override
    public void entryUpdated(EntryEvent<ClusterProcess, Boolean> event) {
      if (event.getValue()) {
        listeners.forEach(appStateListener -> appStateListener.onAppStateOperational(event.getKey().getProcessId()));
      }
    }

    @Override
    public void entryEvicted(EntryEvent<ClusterProcess, Boolean> event) {
      // Ignore it
    }

    @Override
    public void mapCleared(MapEvent event) {
      // Ignore it
    }

    @Override
    public void mapEvicted(MapEvent event) {
      // Ignore it
    }
  }

  private class ConnectedClientListener implements ClientListener {
    @Override
    public void clientConnected(Client client) {
      hzInstance.getSet(LOCAL_MEMBER_UUIDS).add(client.getUuid());
    }

    @Override
    public void clientDisconnected(Client client) {
      hzInstance.getSet(LOCAL_MEMBER_UUIDS).remove(client.getUuid());
    }
  }

  private class NodeDisconnectedListener implements MembershipListener {
    @Override
    public void memberAdded(MembershipEvent membershipEvent) {
      // Nothing to do
    }

    @Override
    public void memberRemoved(MembershipEvent membershipEvent) {
      removeOperationalProcess(membershipEvent.getMember().getUuid());
      if (membershipEvent.getMembers().stream()
        .noneMatch(this::isAppNode)) {
        purgeSharedMemoryForAppNodes();
      }
    }

    @Override
    public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {
      // Nothing to do
    }

    private boolean isAppNode(Member member) {
      return NodeType.APPLICATION.getValue().equals(member.getStringAttribute(NODE_TYPE));
    }

    private void removeOperationalProcess(String uuid) {
      for (ClusterProcess clusterProcess : operationalProcesses.keySet()) {
        if (clusterProcess.getNodeUuid().equals(uuid)) {
          LOGGER.debug("Set node process off for [{}:{}] : ", clusterProcess.getNodeUuid(), clusterProcess.getProcessId());
          hzInstance.getReplicatedMap(OPERATIONAL_PROCESSES).put(clusterProcess, Boolean.FALSE);
        }
      }
    }

    private void purgeSharedMemoryForAppNodes() {
      LOGGER.info("No more application nodes, clearing cluster information about application nodes.");
      hzInstance.getAtomicReference(LEADER).clear();
      hzInstance.getAtomicReference(SONARQUBE_VERSION).clear();
    }
  }
}
