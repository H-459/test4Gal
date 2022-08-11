package com.hubspot.baragon.data;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.baragon.config.ZooKeeperConfiguration;
import com.hubspot.baragon.models.BaragonRequest;
import com.hubspot.baragon.models.InternalRequestStates;
import com.hubspot.baragon.models.QueuedRequestId;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class BaragonRequestDatastore extends AbstractDataStore {
  private static final Logger LOG = LoggerFactory.getLogger(
    BaragonRequestDatastore.class
  );

  public static final String REQUESTS_FORMAT = "/request";
  public static final String REQUEST_FORMAT = REQUESTS_FORMAT + "/%s";
  public static final String REQUEST_STATE_FORMAT = REQUEST_FORMAT + "/status";
  public static final String REQUEST_MESSAGE_FORMAT = REQUEST_FORMAT + "/message";

  public static final String REQUEST_QUEUE_FORMAT = "/queue";
  public static final String REQUEST_ENQUEUE_FORMAT = REQUEST_QUEUE_FORMAT + "/%s|%s|";
  public static final String REQUEST_QUEUE_ITEM_FORMAT = REQUEST_QUEUE_FORMAT + "/%s";

  @Inject
  public BaragonRequestDatastore(
    CuratorFramework curatorFramework,
    ObjectMapper objectMapper,
    ZooKeeperConfiguration zooKeeperConfiguration
  ) {
    super(curatorFramework, objectMapper, zooKeeperConfiguration);
  }

  //
  // REQUEST DATA
  //
  @Timed
  public Optional<BaragonRequest> getRequest(String requestId) {
    return readFromZk(String.format(REQUEST_FORMAT, requestId), BaragonRequest.class);
  }

  @Timed
  public Optional<BaragonRequest> deleteRequest(String requestId) {
    final Optional<BaragonRequest> maybeRequest = getRequest(requestId);

    if (maybeRequest.isPresent()) {
      deleteNode(String.format(REQUEST_FORMAT, requestId), true);
    }

    return maybeRequest;
  }

  @Timed
  public BaragonRequest updateRequest(BaragonRequest request) throws Exception {
    final Optional<BaragonRequest> maybeRequest = getRequest(
      request.getLoadBalancerRequestId()
    );

    if (!maybeRequest.isPresent()) {
      throw new IllegalStateException("No such request exists!");
    }

    final String requestPath = String.format(
      REQUEST_FORMAT,
      request.getLoadBalancerRequestId()
    );

    writeToZk(requestPath, request);

    return maybeRequest.get();
  }

  @Timed
  public List<String> getAllRequestIds() {
    return getChildren(REQUESTS_FORMAT);
  }

  @Timed
  public Optional<Long> getRequestUpdatedAt(String requestId) {
    return getUpdatedAt(String.format(String.format(REQUEST_STATE_FORMAT, requestId)));
  }

  //
  // REQUEST STATE
  //
  public boolean activeRequestExists(String requestId) {
    return nodeExists(String.format(REQUEST_FORMAT, requestId));
  }

  @Timed
  public Optional<InternalRequestStates> getRequestState(String requestId) {
    return readFromZk(
      String.format(REQUEST_STATE_FORMAT, requestId),
      InternalRequestStates.class
    );
  }

  @Timed
  public void setRequestState(String requestId, InternalRequestStates state) {
    writeToZk(String.format(REQUEST_STATE_FORMAT, requestId), state);
  }

  // REQUEST MESSAGE
  @Timed
  public Optional<String> getRequestMessage(String requestId) {
    return readFromZk(String.format(REQUEST_MESSAGE_FORMAT, requestId), String.class);
  }

  @Timed
  public void setRequestMessage(String requestId, String message) {
    writeToZk(String.format(REQUEST_MESSAGE_FORMAT, requestId), message);
  }

  //
  // REQUEST QUEUING
  //
  @Timed
  public QueuedRequestId enqueueRequest(
    BaragonRequest request,
    InternalRequestStates state
  )
    throws NodeExistsException {
    final long start = System.currentTimeMillis();

    final String queuedRequestPath = String.format(
      REQUEST_ENQUEUE_FORMAT,
      request.getLoadBalancerService().getServiceId(),
      request.getLoadBalancerRequestId()
    );
    final String requestPath = String.format(
      REQUEST_FORMAT,
      request.getLoadBalancerRequestId()
    );
    final String requestStatePath = String.format(
      REQUEST_STATE_FORMAT,
      request.getLoadBalancerRequestId()
    );

    try {
      if (!nodeExists(REQUESTS_FORMAT)) {
        createNode(REQUESTS_FORMAT);
      }
      if (!nodeExists(REQUEST_QUEUE_FORMAT)) {
        createNode(REQUEST_QUEUE_FORMAT);
      }

      byte[] requestBytes = objectMapper.writeValueAsBytes(request);
      byte[] stateBytes = objectMapper.writeValueAsBytes(state);

      Collection<CuratorTransactionResult> results = curatorFramework
        .inTransaction()
        .create()
        .forPath(requestPath, requestBytes)
        .and()
        .create()
        .forPath(requestStatePath, stateBytes)
        .and()
        .create()
        .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
        .forPath(queuedRequestPath)
        .and()
        .commit();

      log(
        OperationType.WRITE,
        Optional.of(3),
        Optional.of(requestBytes.length + stateBytes.length),
        start,
        String.format(
          "Transaction Paths [%s + %s + %s]",
          requestPath,
          requestStatePath,
          queuedRequestPath
        )
      );

      return QueuedRequestId.fromString(
        ZKPaths.getNodeFromPath(
          Iterables
            .find(
              results,
              CuratorTransactionResult.ofTypeAndPath(
                org.apache.curator.framework.api.transaction.OperationType.CREATE,
                queuedRequestPath
              )
            )
            .getResultPath()
        )
      );
    } catch (NodeExistsException nee) {
      throw nee;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Timed
  public List<QueuedRequestId> getQueuedRequestIds() {
    final List<String> nodes = getChildren(REQUEST_QUEUE_FORMAT);

    Collections.sort(nodes, SEQUENCE_NODE_COMPARATOR_LOW_TO_HIGH);

    final List<QueuedRequestId> queuedRequestIds = Lists.newArrayListWithCapacity(
      nodes.size()
    );

    for (String node : nodes) {
      queuedRequestIds.add(QueuedRequestId.fromString(node));
    }

    return queuedRequestIds;
  }

  public long getOldestQueuedRequestAge() {
    long now = System.currentTimeMillis();
    long oldest = now;
    for (String child : getChildren(REQUEST_QUEUE_FORMAT)) {
      try {
        Stat stat = curatorFramework
          .checkExists()
          .forPath(ZKPaths.makePath(REQUEST_QUEUE_FORMAT, child));
        if (stat != null && stat.getMtime() < oldest) {
          oldest = stat.getMtime();
        }
      } catch (Exception e) {
        LOG.warn("Could not check exists for queued request id {}", child);
      }
    }
    return now - oldest;
  }

  @Timed
  public int getQueuedRequestCount() {
    return getChildren(REQUEST_QUEUE_FORMAT).size();
  }

  @Timed
  public void removeQueuedRequest(QueuedRequestId queuedRequestId) {
    deleteNode(String.format(REQUEST_QUEUE_ITEM_FORMAT, queuedRequestId.buildZkPath()));
  }
}
