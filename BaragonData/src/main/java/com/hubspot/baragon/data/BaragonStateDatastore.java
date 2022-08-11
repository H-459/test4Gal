package com.hubspot.baragon.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.baragon.config.ZooKeeperConfiguration;
import com.hubspot.baragon.models.BaragonRequest;
import com.hubspot.baragon.models.BaragonService;
import com.hubspot.baragon.models.BaragonServiceState;
import com.hubspot.baragon.models.UpstreamInfo;
import com.hubspot.baragon.utils.ZkParallelFetcher;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransaction;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class BaragonStateDatastore extends AbstractDataStore {
  private static final Logger LOG = LoggerFactory.getLogger(BaragonStateDatastore.class);

  public static final String SERVICES_FORMAT = "/state";
  public static final String LAST_UPDATED_FORMAT = "/state-last-updated";
  public static final String SERVICE_FORMAT = SERVICES_FORMAT + "/%s";
  public static final String UPSTREAM_FORMAT = SERVICE_FORMAT + "/%s";

  private final ZkParallelFetcher zkFetcher;

  @Inject
  public BaragonStateDatastore(
    CuratorFramework curatorFramework,
    ObjectMapper objectMapper,
    ZkParallelFetcher zkFetcher,
    ZooKeeperConfiguration zooKeeperConfiguration
  ) {
    super(curatorFramework, objectMapper, zooKeeperConfiguration);
    this.zkFetcher = zkFetcher;
  }

  public Collection<String> getServices() {
    return getChildren(SERVICES_FORMAT);
  }

  public boolean serviceExists(String serviceId) {
    return nodeExists(String.format(SERVICE_FORMAT, serviceId));
  }

  public Optional<BaragonService> getService(String serviceId) {
    return readFromZk(String.format(SERVICE_FORMAT, serviceId), BaragonService.class);
  }

  public void removeService(String serviceId) {
    for (String upstream : getUpstreamNodes(serviceId)) {
      deleteNode(String.format(UPSTREAM_FORMAT, serviceId, upstream));
    }

    deleteNode(String.format(SERVICE_FORMAT, serviceId));
  }

  private Collection<String> getUpstreamNodes(String serviceId) {
    return getChildren(String.format(SERVICE_FORMAT, serviceId));
  }

  public Collection<UpstreamInfo> getUpstreams(String serviceId) {
    final Collection<String> upstreamNodes = getUpstreamNodes(serviceId);
    final Collection<UpstreamInfo> upstreams = new ArrayList<>(upstreamNodes.size());
    for (String node : upstreamNodes) {
      upstreams.add(UpstreamInfo.fromString(node));
    }
    return upstreams;
  }

  public void saveService(BaragonService service) {
    String servicePath = String.format(SERVICE_FORMAT, service.getServiceId());
    writeToZk(servicePath, service);
  }

  public boolean isServiceUnchanged(BaragonRequest update) {
    if (update.isUpstreamUpdateOnly()) {
      return true;
    }

    Optional<BaragonService> maybeExistingService = getService(
      update.getLoadBalancerService().getServiceId()
    );

    if (!maybeExistingService.isPresent()) {
      return false;
    }

    if (update.getLoadBalancerService().equals(maybeExistingService.get())) {
      return true;
    }

    return false;
  }

  public void updateService(BaragonRequest request) throws Exception {
    if (!nodeExists(SERVICES_FORMAT)) {
      createNode(SERVICES_FORMAT);
    }

    String serviceId = request.getLoadBalancerService().getServiceId();
    Collection<UpstreamInfo> currentUpstreams = getUpstreams(serviceId);
    String servicePath = String.format(SERVICE_FORMAT, serviceId);
    CuratorTransaction transaction = curatorFramework.inTransaction();
    if (nodeExists(servicePath) && !isServiceUnchanged(request)) {
      LOG.trace(
        "Updating existing service {}",
        request.getLoadBalancerService().getServiceId()
      );
      transaction =
        curatorFramework
          .inTransaction()
          .setData()
          .forPath(servicePath, serialize(request.getLoadBalancerService()))
          .and();
    } else if (!nodeExists(servicePath)) {
      LOG.trace(
        "Creating new node for service {}",
        request.getLoadBalancerService().getServiceId()
      );
      transaction =
        curatorFramework
          .inTransaction()
          .create()
          .forPath(servicePath, serialize(request.getLoadBalancerService()))
          .and();
    }
    // Otherwise, the service node exists, but it hasn't changed, so don't update it.

    Set<String> pathsToDelete = new HashSet<>();
    if (!request.getReplaceUpstreams().isEmpty()) {
      for (UpstreamInfo upstreamInfo : currentUpstreams) {
        deleteMatchingUpstreams(
          serviceId,
          currentUpstreams,
          transaction,
          pathsToDelete,
          upstreamInfo
        );
      }
      for (UpstreamInfo upstreamInfo : request.getReplaceUpstreams()) {
        String addPath = String.format(UPSTREAM_FORMAT, serviceId, upstreamInfo.toPath());
        if (!nodeExists(addPath) || pathsToDelete.contains(addPath)) {
          transaction.create().forPath(addPath).and();
        }
      }
    } else {
      LOG.debug("Removing upstreams {}", request.getRemoveUpstreams());
      for (UpstreamInfo upstreamInfo : request.getRemoveUpstreams()) {
        deleteMatchingUpstreams(
          serviceId,
          currentUpstreams,
          transaction,
          pathsToDelete,
          upstreamInfo
        );
      }

      LOG.debug("Adding upstreams {}", request.getAddUpstreams());
      for (UpstreamInfo upstreamInfo : request.getAddUpstreams()) {
        String addPath = String.format(UPSTREAM_FORMAT, serviceId, upstreamInfo.toPath());
        List<String> matchingUpstreamPaths = matchingUpstreamHostPorts(
          currentUpstreams,
          upstreamInfo
        );
        for (String matchingPath : matchingUpstreamPaths) {
          String fullPath = String.format(UPSTREAM_FORMAT, serviceId, matchingPath);
          if (
            nodeExists(fullPath) &&
            !pathsToDelete.contains(fullPath) &&
            !fullPath.equals(addPath)
          ) {
            LOG.info(
              "Deleting existing upstream {} because it matches new upstream",
              fullPath
            );
            pathsToDelete.add(fullPath);
            transaction.delete().forPath(fullPath).and();
          }
        }
        boolean nodeExists = nodeExists(addPath);
        LOG.trace(
          "About to check if we should create a new upstream node at {}. nodeExists: {}; pathsToDelete: {}",
          addPath,
          nodeExists,
          pathsToDelete
        );
        if (!nodeExists || pathsToDelete.contains(addPath)) {
          LOG.info("Creating new upstream node {}", addPath);
          transaction.create().forPath(addPath).and();
        }
      }
    }

    LOG.trace("pathsToDelete right before the commit: {}", pathsToDelete);
    ((CuratorTransactionFinal) transaction).commit();
  }

  private void deleteMatchingUpstreams(
    String serviceId,
    Collection<UpstreamInfo> currentUpstreams,
    CuratorTransaction transaction,
    Set<String> pathsToDelete,
    UpstreamInfo upstreamInfo
  )
    throws Exception {
    List<String> matchingUpstreamPaths = matchingUpstreamHostPorts(
      currentUpstreams,
      upstreamInfo
    );
    if (matchingUpstreamPaths.isEmpty()) {
      LOG.warn(
        "No upstream node found to delete for {}, current upstream nodes are {}",
        upstreamInfo,
        currentUpstreams
      );
    } else {
      for (String matchingPath : matchingUpstreamPaths) {
        String fullPath = String.format(UPSTREAM_FORMAT, serviceId, matchingPath);
        if (nodeExists(fullPath) && !pathsToDelete.contains(fullPath)) {
          LOG.info("Deleting {}", fullPath);
          pathsToDelete.add(fullPath);
          transaction.delete().forPath(fullPath).and();
        }
      }
    }
  }

  private List<String> matchingUpstreamHostPorts(
    Collection<UpstreamInfo> currentUpstreams,
    UpstreamInfo toAdd
  ) {
    List<String> matchingPaths = new ArrayList<>();
    for (UpstreamInfo upstreamInfo : currentUpstreams) {
      if (UpstreamInfo.upstreamAndGroupMatches(upstreamInfo, toAdd)) {
        matchingPaths.add(upstreamInfo.getOriginalPath().or(upstreamInfo.getUpstream()));
      }
    }
    return matchingPaths;
  }

  public Collection<BaragonServiceState> getGlobalState() {
    try {
      LOG.info("Starting to compute all service states");
      return computeAllServiceStates();
    } catch (Exception e) {
      throw Throwables.propagate(e);
    } finally {
      LOG.info("Finished computing all service states");
    }
  }

  public byte[] getGlobalStateAsBytes() {
    return serialize(getGlobalState());
  }

  public void incrementStateVersion() {
    writeToZk(LAST_UPDATED_FORMAT, System.currentTimeMillis());
  }

  public Optional<Integer> getStateVersion() {
    try {
      final Stat stat = curatorFramework.checkExists().forPath(LAST_UPDATED_FORMAT);

      if (stat != null) {
        return Optional.of(stat.getVersion());
      } else {
        return Optional.absent();
      }
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  private Collection<BaragonServiceState> computeAllServiceStates() throws Exception {
    Collection<String> services = new ArrayList<>();

    for (String service : getServices()) {
      services.add(ZKPaths.makePath(SERVICES_FORMAT, service));
    }

    final Map<String, BaragonService> serviceMap = zkFetcher.fetchDataInParallel(
      services,
      new BaragonDeserializer<>(objectMapper, BaragonService.class)
    );
    final Map<String, Collection<UpstreamInfo>> serviceToUpstreamInfoMap = fetchServiceToUpstreamInfoMap(
      services
    );
    final Collection<BaragonServiceState> serviceStates = new ArrayList<>(
      serviceMap.size()
    );

    for (final Entry<String, BaragonService> serviceEntry : serviceMap.entrySet()) {
      BaragonService service = serviceEntry.getValue();
      Collection<UpstreamInfo> upstreams = serviceToUpstreamInfoMap.get(
        serviceEntry.getKey()
      );
      serviceStates.add(
        new BaragonServiceState(
          service,
          MoreObjects.firstNonNull(upstreams, Collections.<UpstreamInfo>emptyList())
        )
      );
    }

    return serviceStates;
  }

  private Map<String, Collection<UpstreamInfo>> fetchServiceToUpstreamInfoMap(
    Collection<String> services
  )
    throws Exception {
    Map<String, Collection<String>> serviceToUpstreams = zkFetcher.fetchChildrenInParallel(
      services
    );
    Map<String, Collection<UpstreamInfo>> serviceToUpstreamInfo = new HashMap<>(
      services.size()
    );

    for (Entry<String, Collection<String>> entry : serviceToUpstreams.entrySet()) {
      for (String upstream : entry.getValue()) {
        if (!serviceToUpstreamInfo.containsKey(entry.getKey())) {
          serviceToUpstreamInfo.put(
            entry.getKey(),
            Lists.newArrayList(UpstreamInfo.fromString(upstream))
          );
        } else {
          serviceToUpstreamInfo
            .get(entry.getKey())
            .add(UpstreamInfo.fromString(upstream));
        }
      }
    }

    return serviceToUpstreamInfo;
  }

  public Optional<UpstreamInfo> getUpstreamInfo(String serviceId, String upstream) {
    return readFromZk(
      String.format(UPSTREAM_FORMAT, serviceId, upstream),
      UpstreamInfo.class
    );
  }

  public static class BaragonDeserializer<T> implements Function<byte[], T> {
    private final Class<T> clazz;
    private final ObjectMapper objectMapper;

    public BaragonDeserializer(ObjectMapper objectMapper, Class<T> clazz) {
      this.clazz = clazz;
      this.objectMapper = objectMapper;
    }

    @Override
    public T apply(byte[] input) {
      try {
        return objectMapper.readValue(input, clazz);
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
  }
}
