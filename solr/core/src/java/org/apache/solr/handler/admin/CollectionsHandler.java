/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.admin;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient.Builder;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest.RequestSyncShard;
import org.apache.solr.client.solrj.response.RequestStatusState;
import org.apache.solr.client.solrj.util.SolrIdentifierValidator;
import org.apache.solr.cloud.OverseerSolrResponse;
import org.apache.solr.cloud.OverseerSolrResponseSerializer;
import org.apache.solr.cloud.OverseerTaskQueue;
import org.apache.solr.cloud.OverseerTaskQueue.QueueEvent;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.cloud.ZkController.NotInClusterStateException;
import org.apache.solr.cloud.ZkShardTerms;
import org.apache.solr.cloud.api.collections.DistributedCollectionConfigSetCommandRunner;
import org.apache.solr.cloud.api.collections.ReindexCollectionCmd;
import org.apache.solr.cloud.api.collections.RoutedAlias;
import org.apache.solr.cloud.overseer.SliceMutator;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.Aliases;
import org.apache.solr.common.cloud.ClusterProperties;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.CollectionProperties;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.ImplicitDocRouter;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Replica.State;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkCmdExecutor;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.CollectionParams.CollectionAction;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CloudConfig;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.backup.BackupFilePaths;
import org.apache.solr.core.backup.BackupId;
import org.apache.solr.core.backup.BackupManager;
import org.apache.solr.core.backup.BackupProperties;
import org.apache.solr.core.backup.repository.BackupRepository;
import org.apache.solr.core.snapshots.CollectionSnapshotMetaData;
import org.apache.solr.core.snapshots.SolrSnapshotManager;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.util.tracing.TraceUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.apache.solr.client.solrj.response.RequestStatusState.*;
import static org.apache.solr.cloud.Overseer.QUEUE_OPERATION;
import static org.apache.solr.cloud.api.collections.CollectionHandlingUtils.REQUESTID;
import static org.apache.solr.cloud.api.collections.CollectionHandlingUtils.*;
import static org.apache.solr.cloud.api.collections.RoutedAlias.CREATE_COLLECTION_PREFIX;
import static org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST;
import static org.apache.solr.common.cloud.DocCollection.DOC_ROUTER;
import static org.apache.solr.common.cloud.DocCollection.PER_REPLICA_STATE;
import static org.apache.solr.common.cloud.ZkStateReader.REPLICA_TYPE;
import static org.apache.solr.common.cloud.ZkStateReader.*;
import static org.apache.solr.common.params.CollectionAdminParams.COLLECTION;
import static org.apache.solr.common.params.CollectionAdminParams.PROPERTY_PREFIX;
import static org.apache.solr.common.params.CollectionAdminParams.*;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.*;
import static org.apache.solr.common.params.CommonAdminParams.*;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.params.CommonParams.*;
import static org.apache.solr.common.params.CoreAdminParams.*;
import static org.apache.solr.common.params.ShardParams._ROUTE_;
import static org.apache.solr.common.util.StrUtils.formatString;

public class CollectionsHandler extends RequestHandlerBase implements PermissionNameProvider {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected final CoreContainer coreContainer;
  private final Optional<DistributedCollectionConfigSetCommandRunner> distributedCollectionConfigSetCommandRunner;

  public CollectionsHandler() {
    // Unlike most request handlers, CoreContainer initialization
    // should happen in the constructor...
    this(null);
  }


  /**
   * Overloaded ctor to inject CoreContainer into the handler.
   *
   * @param coreContainer Core Container of the solr webapp installed.
   */
  public CollectionsHandler(final CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
    distributedCollectionConfigSetCommandRunner = coreContainer != null ? coreContainer.getDistributedCollectionCommandRunner() : Optional.empty();
  }

  @Override
  public PermissionNameProvider.Name getPermissionName(AuthorizationContext ctx) {
    String action = ctx.getParams().get("action");
    if (action == null) return PermissionNameProvider.Name.COLL_READ_PERM;
    CollectionParams.CollectionAction collectionAction = CollectionParams.CollectionAction.get(action);
    if (collectionAction == null) return null;
    return collectionAction.isWrite ?
        PermissionNameProvider.Name.COLL_EDIT_PERM :
        PermissionNameProvider.Name.COLL_READ_PERM;
  }

  @Override
  final public void init(NamedList<?> args) {

  }

  /**
   * The instance of CoreContainer this handler handles. This should be the CoreContainer instance that created this
   * handler.
   *
   * @return a CoreContainer instance
   */
  public CoreContainer getCoreContainer() {
    return this.coreContainer;
  }

  protected void copyFromClusterProp(Map<String, Object> props, String prop) throws IOException {
    if (props.get(prop) != null) return;//if it's already specified , return
    Object defVal = new ClusterProperties(coreContainer.getZkController().getZkStateReader().getZkClient())
        .getClusterProperty(ImmutableList.of(CollectionAdminParams.DEFAULTS, CollectionAdminParams.COLLECTION, prop), null);
    if (defVal != null) props.put(prop, String.valueOf(defVal));
  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    // Make sure the cores is enabled
    CoreContainer cores = checkErrors();

    // Pick the action
    SolrParams params = req.getParams();
    String a = params.get(CoreAdminParams.ACTION);
    if (a != null) {
      CollectionAction action = CollectionAction.get(a);
      if (action == null) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown action: " + a);
      }
      final String collection = params.get(COLLECTION);
      MDCLoggingContext.setCollection(collection);
      TraceUtils.setDbInstance(req, collection);
      CollectionOperation operation = CollectionOperation.get(action);
      if (log.isDebugEnabled()) {
        log.debug("Invoked Collection Action: {} with params {}", action.toLower(), req.getParamString());
      }
      invokeAction(req, rsp, cores, action, operation);
    } else {
      throw new SolrException(ErrorCode.BAD_REQUEST, "action is a required param");
    }
    rsp.setHttpCaching(false);
  }

  protected CoreContainer checkErrors() {
    CoreContainer cores = getCoreContainer();
    if (cores == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Core container instance missing");
    }

    // Make sure that the core is ZKAware
    if (!cores.isZooKeeperAware()) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Solr instance is not running in SolrCloud mode.");
    }
    return cores;
  }

  @SuppressWarnings({"unchecked"})
  void invokeAction(SolrQueryRequest req, SolrQueryResponse rsp, CoreContainer cores, CollectionAction action, CollectionOperation operation) throws Exception {
    if (!coreContainer.isZooKeeperAware()) {
      throw new SolrException(BAD_REQUEST,
          "Invalid request. collections can be accessed only in SolrCloud mode");
    }
    Map<String, Object> props = operation.execute(req, rsp, this);
    if (props == null) {
      return;
    }

    String asyncId = req.getParams().get(ASYNC);
    if (asyncId != null) {
      props.put(ASYNC, asyncId);
    }

    props.put(QUEUE_OPERATION, operation.action.toLower());

    ZkNodeProps zkProps = new ZkNodeProps(props);
    final SolrResponse overseerResponse;

    overseerResponse = submitCollectionApiCommand(zkProps, operation.action, operation.timeOut);

    rsp.getValues().addAll(overseerResponse.getResponse());
    Exception exp = overseerResponse.getException();
    if (exp != null) {
      rsp.setException(exp);
    }

    // Even if Overseer does wait for the collection to be created, it sees a different cluster state than this node,
    // so this wait is required to make sure the local node Zookeeper watches fired and now see the collection.
    if (action.equals(CollectionAction.CREATE) && asyncId == null) {
      if (rsp.getException() == null) {
        waitForActiveCollection(zkProps.getStr(NAME), cores, overseerResponse);
      }
    }
  }

  static final Set<String> KNOWN_ROLES = ImmutableSet.of("overseer");

  public static long DEFAULT_COLLECTION_OP_TIMEOUT = 180 * 1000;

  public SolrResponse submitCollectionApiCommand(ZkNodeProps m, CollectionAction action) throws KeeperException, InterruptedException {
    return submitCollectionApiCommand(m, action, DEFAULT_COLLECTION_OP_TIMEOUT);
  }

  public SolrResponse submitCollectionApiCommand(ZkNodeProps m, CollectionAction action, long timeout) throws KeeperException, InterruptedException {
    // Collection API messages are either sent to Overseer and processed there, or processed locally.
    // Distributing Collection API implies we're also distributing Cluster State Updates. Indeed collection creation
    // with non distributed cluster state updates requires for "Per Replica States" that the Collection API be running
    // on Overseer, which means that it is not possible to distributed Collection API while keeping cluster state updates
    // on Overseer. See the call to CollectionCommandContext.submitIntraProcessMessage() in CreateCollectionCmd.call() which
    // can only be done if the Collection API command runs on the same JVM as the Overseer based cluster state update...
    // The configuration handling includes these checks to not allow distributing collection API without distributing
    // cluster state updates (but the other way around is ok). See constructor of CloudConfig.
    if (distributedCollectionConfigSetCommandRunner.isPresent()) {
      return distributedCollectionConfigSetCommandRunner.get().runCollectionCommand(m, action, timeout);
    } else { // Sending the Collection API message to Overseer via a Zookeeper queue
      String operation = m.getStr(QUEUE_OPERATION);
      if (operation == null) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "missing key " + QUEUE_OPERATION);
      }
      if (m.get(ASYNC) != null) {
        String asyncId = m.getStr(ASYNC);
        NamedList<Object> r = new NamedList<>();

        if (coreContainer.getZkController().claimAsyncId(asyncId)) {
          boolean success = false;
          try {
            coreContainer.getZkController().getOverseerCollectionQueue()
                .offer(Utils.toJSON(m));
            success = true;
          } finally {
            if (!success) {
              try {
                coreContainer.getZkController().clearAsyncId(asyncId);
              } catch (Exception e) {
                // let the original exception bubble up
                log.error("Unable to release async ID={}", asyncId, e);
                SolrZkClient.checkInterrupted(e);
              }
            }
          }
        } else {
          r.add("error", "Task with the same requestid already exists. (" + asyncId + ")");
        }
        r.add(CoreAdminParams.REQUESTID, m.get(ASYNC));

        return new OverseerSolrResponse(r);
      }

      long time = System.nanoTime();
      QueueEvent event = coreContainer.getZkController()
          .getOverseerCollectionQueue()
          .offer(Utils.toJSON(m), timeout);
      if (event.getBytes() != null) {
        return OverseerSolrResponseSerializer.deserialize(event.getBytes());
      } else {
        if (System.nanoTime() - time >= TimeUnit.NANOSECONDS.convert(timeout, TimeUnit.MILLISECONDS)) {
          throw new SolrException(ErrorCode.SERVER_ERROR, operation
              + " the collection time out:" + timeout / 1000 + "s");
        } else if (event.getWatchedEvent() != null) {
          throw new SolrException(ErrorCode.SERVER_ERROR, operation
              + " the collection error [Watcher fired on path: "
              + event.getWatchedEvent().getPath() + " state: "
              + event.getWatchedEvent().getState() + " type "
              + event.getWatchedEvent().getType() + "]");
        } else {
          throw new SolrException(ErrorCode.SERVER_ERROR, operation
              + " the collection unknown case");
        }
      }
    }
  }

  private boolean overseerCollectionQueueContains(String asyncId) throws KeeperException, InterruptedException {
    OverseerTaskQueue collectionQueue = coreContainer.getZkController().getOverseerCollectionQueue();
    return collectionQueue.containsTaskWithRequestId(ASYNC, asyncId);
  }

  /**
   * Copy prefixed params into a map.  There must only be one value for these parameters.
   *
   * @param params The source of params from which copies should be made
   * @param props  The map into which param names and values should be copied as keys and values respectively
   * @param prefix The prefix to select.
   * @return the map supplied in the props parameter, modified to contain the prefixed params.
   */
  private static Map<String, Object> copyPropertiesWithPrefix(SolrParams params, Map<String, Object> props, String prefix) {
    Iterator<String> iter = params.getParameterNamesIterator();
    while (iter.hasNext()) {
      String param = iter.next();
      if (param.startsWith(prefix)) {
        final String[] values = params.getParams(param);
        if (values.length != 1) {
          throw new SolrException(BAD_REQUEST, "Only one value can be present for parameter " + param);
        }
        props.put(param, values[0]);
      }
    }
    return props;
  }

  public static ModifiableSolrParams params(String... params) {
    ModifiableSolrParams msp = new ModifiableSolrParams();
    for (int i = 0; i < params.length; i += 2) {
      msp.add(params[i], params[i + 1]);
    }
    return msp;
  }

  //////////////////////// SolrInfoMBeans methods //////////////////////

  @Override
  public String getDescription() {
    return "Manage SolrCloud Collections";
  }

  @Override
  public Category getCategory() {
    return Category.ADMIN;
  }

  private static void createSysConfigSet(CoreContainer coreContainer) throws KeeperException, InterruptedException {
    SolrZkClient zk = coreContainer.getZkController().getZkStateReader().getZkClient();
    ZkCmdExecutor cmdExecutor = new ZkCmdExecutor(zk.getZkClientTimeout());
    cmdExecutor.ensureExists(ZkStateReader.CONFIGS_ZKNODE, zk);
    cmdExecutor.ensureExists(ZkStateReader.CONFIGS_ZKNODE + "/" + CollectionAdminParams.SYSTEM_COLL, zk);

    try {
      String path = ZkStateReader.CONFIGS_ZKNODE + "/" + CollectionAdminParams.SYSTEM_COLL + "/schema.xml";
      byte[] data = IOUtils.toByteArray(CollectionsHandler.class.getResourceAsStream("/SystemCollectionSchema.xml"));
      assert data != null && data.length > 0;
      cmdExecutor.ensureExists(path, data, CreateMode.PERSISTENT, zk);
      path = ZkStateReader.CONFIGS_ZKNODE + "/" + CollectionAdminParams.SYSTEM_COLL + "/solrconfig.xml";
      data = IOUtils.toByteArray(CollectionsHandler.class.getResourceAsStream("/SystemCollectionSolrConfig.xml"));
      assert data != null && data.length > 0;
      cmdExecutor.ensureExists(path, data, CreateMode.PERSISTENT, zk);
    } catch (IOException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }


  }

  private static void addStatusToResponse(NamedList<Object> results, RequestStatusState state, String msg) {
    SimpleOrderedMap<String> status = new SimpleOrderedMap<>();
    status.add("state", state.getKey());
    status.add("msg", msg);
    results.add("status", status);
  }

  public enum CollectionOperation implements CollectionOp {
    CREATE_OP(CREATE, (req, rsp, h) -> {
      Map<String, Object> props = copy(req.getParams().required(), null, NAME);
      props.put("fromApi", "true");
      copy(req.getParams(), props,
          REPLICATION_FACTOR,
          COLL_CONF,
          NUM_SLICES,
          CREATE_NODE_SET,
          CREATE_NODE_SET_SHUFFLE,
          SHARDS_PROP,
          PULL_REPLICAS,
          TLOG_REPLICAS,
          NRT_REPLICAS,
          WAIT_FOR_FINAL_STATE,
          PER_REPLICA_STATE,
          ALIAS);

      if (props.get(REPLICATION_FACTOR) != null && props.get(NRT_REPLICAS) != null) {
        //TODO: Remove this in 8.0 . Keep this for SolrJ client back-compat. See SOLR-11676 for more details
        int replicationFactor = Integer.parseInt((String) props.get(REPLICATION_FACTOR));
        int nrtReplicas = Integer.parseInt((String) props.get(NRT_REPLICAS));
        if (replicationFactor != nrtReplicas) {
          throw new SolrException(ErrorCode.BAD_REQUEST,
              "Cannot specify both replicationFactor and nrtReplicas as they mean the same thing");
        }
      }
      if (props.get(REPLICATION_FACTOR) != null) {
        props.put(NRT_REPLICAS, props.get(REPLICATION_FACTOR));
      } else if (props.get(NRT_REPLICAS) != null) {
        props.put(REPLICATION_FACTOR, props.get(NRT_REPLICAS));
      }

      final String collectionName = SolrIdentifierValidator.validateCollectionName((String) props.get(NAME));
      final String shardsParam = (String) props.get(SHARDS_PROP);
      if (StringUtils.isNotEmpty(shardsParam)) {
        verifyShardsParam(shardsParam);
      }
      if (CollectionAdminParams.SYSTEM_COLL.equals(collectionName)) {
        //We must always create a .system collection with only a single shard
        props.put(NUM_SLICES, 1);
        props.remove(SHARDS_PROP);
        createSysConfigSet(h.coreContainer);

      }
      if (shardsParam == null) h.copyFromClusterProp(props, NUM_SLICES);
      for (String prop : ImmutableSet.of(NRT_REPLICAS, PULL_REPLICAS, TLOG_REPLICAS))
        h.copyFromClusterProp(props, prop);
      copyPropertiesWithPrefix(req.getParams(), props, PROPERTY_PREFIX);
      return copyPropertiesWithPrefix(req.getParams(), props, "router.");

    }),
    @SuppressWarnings({"unchecked"})
    COLSTATUS_OP(COLSTATUS, (req, rsp, h) -> {
      Map<String, Object> props = copy(req.getParams(), null,
          COLLECTION_PROP,
          ColStatus.CORE_INFO_PROP,
          ColStatus.SEGMENTS_PROP,
          ColStatus.FIELD_INFO_PROP,
          ColStatus.RAW_SIZE_PROP,
          ColStatus.RAW_SIZE_SUMMARY_PROP,
          ColStatus.RAW_SIZE_DETAILS_PROP,
          ColStatus.RAW_SIZE_SAMPLING_PERCENT_PROP,
          ColStatus.SIZE_INFO_PROP);

      new ColStatus(h.coreContainer.getSolrClientCache(),
          h.coreContainer.getZkController().getZkStateReader().getClusterState(), new ZkNodeProps(props))
          .getColStatus(rsp.getValues());
      return null;
    }),
    DELETE_OP(DELETE, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null, NAME);
      return copy(req.getParams(), map, FOLLOW_ALIASES);
    }),
    // XXX should this command support followAliases?
    RELOAD_OP(RELOAD, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null, NAME);
      return copy(req.getParams(), map);
    }),

    RENAME_OP(RENAME, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null, NAME, CollectionAdminParams.TARGET);
      return copy(req.getParams(), map, FOLLOW_ALIASES);
    }),

    REINDEXCOLLECTION_OP(REINDEXCOLLECTION, (req, rsp, h) -> {
      Map<String, Object> m = copy(req.getParams().required(), null, NAME);
      copy(req.getParams(), m,
          ReindexCollectionCmd.COMMAND,
          ReindexCollectionCmd.REMOVE_SOURCE,
          ReindexCollectionCmd.TARGET,
          ZkStateReader.CONFIGNAME_PROP,
          NUM_SLICES,
          NRT_REPLICAS,
          PULL_REPLICAS,
          TLOG_REPLICAS,
          REPLICATION_FACTOR,
          CREATE_NODE_SET,
          CREATE_NODE_SET_SHUFFLE,
          "shards",
          CommonParams.ROWS,
          CommonParams.Q,
          CommonParams.FL,
          FOLLOW_ALIASES);
      if (req.getParams().get("collection." + ZkStateReader.CONFIGNAME_PROP) != null) {
        m.put(ZkStateReader.CONFIGNAME_PROP, req.getParams().get("collection." + ZkStateReader.CONFIGNAME_PROP));
      }
      copyPropertiesWithPrefix(req.getParams(), m, "router.");
      return m;
    }),

    SYNCSHARD_OP(SYNCSHARD, (req, rsp, h) -> {
      String extCollection = req.getParams().required().get("collection");
      String collection = h.coreContainer.getZkController().getZkStateReader().getAliases().resolveSimpleAlias(extCollection);
      String shard = req.getParams().required().get("shard");

      ClusterState clusterState = h.coreContainer.getZkController().getClusterState();

      DocCollection docCollection = clusterState.getCollection(collection);
      ZkNodeProps leaderProps = docCollection.getLeader(shard);
      ZkCoreNodeProps nodeProps = new ZkCoreNodeProps(leaderProps);

      try (HttpSolrClient client = new Builder(nodeProps.getBaseUrl())
          .withConnectionTimeout(15000)
          .withSocketTimeout(60000)
          .build()) {
        RequestSyncShard reqSyncShard = new RequestSyncShard();
        reqSyncShard.setCollection(collection);
        reqSyncShard.setShard(shard);
        reqSyncShard.setCoreName(nodeProps.getCoreName());
        client.request(reqSyncShard);
      }
      return null;
    }),

    CREATEALIAS_OP(CREATEALIAS, (req, rsp, h) -> {
      String alias = req.getParams().get(NAME);
      SolrIdentifierValidator.validateAliasName(alias);
      String collections = req.getParams().get("collections");
      RoutedAlias routedAlias = null;
      Exception ex = null;
      HashMap<String,Object> possiblyModifiedParams = new HashMap<>();
      try {
        // note that RA specific validation occurs here.
        req.getParams().toMap(possiblyModifiedParams);
        @SuppressWarnings({"unchecked", "rawtypes"})
        // This is awful because RoutedAlias lies about what types it wants
        Map<String, String> temp = (Map<String, String>) (Map) possiblyModifiedParams;
        routedAlias = RoutedAlias.fromProps(alias, temp);
      } catch (SolrException e) {
        // we'll throw this later if we are in fact creating a routed alias.
        ex = e;
      }
      ModifiableSolrParams finalParams = new ModifiableSolrParams();
      for (Map.Entry<String, Object> entry : possiblyModifiedParams.entrySet()) {
        if (entry.getValue().getClass().isArray() ) {
          // v2 api hits this case
          for (Object o : (Object[]) entry.getValue()) {
            finalParams.add(entry.getKey(),o.toString());
          }
        } else {
          finalParams.add(entry.getKey(),entry.getValue().toString());
        }
      }

      if (collections != null) {
        if (routedAlias != null) {
          throw new SolrException(BAD_REQUEST, "Collections cannot be specified when creating a routed alias.");
        } else {
          //////////////////////////////////////
          // Regular alias creation indicated //
          //////////////////////////////////////
          return copy(finalParams.required(), null, NAME, "collections");
        }
      }

      /////////////////////////////////////////////////
      // We are creating a routed alias from here on //
      /////////////////////////////////////////////////

      // If our prior creation attempt had issues expose them now.
      if (ex != null) {
        throw ex;
      }

      // Now filter out just the parameters we care about from the request
      assert routedAlias != null;
      Map<String, Object> result = copy(finalParams, null, routedAlias.getRequiredParams());
      copy(finalParams, result, routedAlias.getOptionalParams());

      ModifiableSolrParams createCollParams = new ModifiableSolrParams(); // without prefix

      // add to result params that start with "create-collection.".
      //   Additionally, save these without the prefix to createCollParams
      for (Map.Entry<String, String[]> entry : finalParams) {
        final String p = entry.getKey();
        if (p.startsWith(CREATE_COLLECTION_PREFIX)) {
          // This is what SolrParams#getAll(Map, Collection)} does
          final String[] v = entry.getValue();
          if (v.length == 1) {
            result.put(p, v[0]);
          } else {
            result.put(p, v);
          }
          createCollParams.set(p.substring(CREATE_COLLECTION_PREFIX.length()), v);
        }
      }

      // Verify that the create-collection prefix'ed params appear to be valid.
      if (createCollParams.get(NAME) != null) {
        throw new SolrException(BAD_REQUEST, "routed aliases calculate names for their " +
            "dependent collections, you cannot specify the name.");
      }
      if (createCollParams.get(COLL_CONF) == null) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            "We require an explicit " + COLL_CONF);
      }
      // note: could insist on a config name here as well.... or wait to throw at overseer
      createCollParams.add(NAME, "TMP_name_TMP_name_TMP"); // just to pass validation
      CREATE_OP.execute(new LocalSolrQueryRequest(null, createCollParams), rsp, h); // ignore results

      return result;
    }),

    DELETEALIAS_OP(DELETEALIAS, (req, rsp, h) -> copy(req.getParams().required(), null, NAME)),

    /**
     * Change properties for an alias (use CREATEALIAS_OP to change the actual value of the alias)
     */
    ALIASPROP_OP(ALIASPROP, (req, rsp, h) -> {
      Map<String, Object> params = copy(req.getParams().required(), null, NAME);

      // Note: success/no-op in the event of no properties supplied is intentional. Keeps code simple and one less case
      // for api-callers to check for.
      return convertPrefixToMap(req.getParams(), params, "property");
    }),

    /**
     * List the aliases and associated properties.
     */
    @SuppressWarnings({"unchecked"})
    LISTALIASES_OP(LISTALIASES, (req, rsp, h) -> {
      ZkStateReader zkStateReader = h.coreContainer.getZkController().getZkStateReader();
      // if someone calls listAliases, lets ensure we return an up to date response
      zkStateReader.aliasesManager.update();
      Aliases aliases = zkStateReader.getAliases();
      if (aliases != null) {
        // the aliases themselves...
        rsp.getValues().add("aliases", aliases.getCollectionAliasMap());
        // Any properties for the above aliases.
        Map<String, Map<String, String>> meta = new LinkedHashMap<>();
        for (String alias : aliases.getCollectionAliasListMap().keySet()) {
          Map<String, String> collectionAliasProperties = aliases.getCollectionAliasProperties(alias);
          if (!collectionAliasProperties.isEmpty()) {
            meta.put(alias, collectionAliasProperties);
          }
        }
        rsp.getValues().add("properties", meta);
      }
      return null;
    }),
    SPLITSHARD_OP(SPLITSHARD, DEFAULT_COLLECTION_OP_TIMEOUT * 5, (req, rsp, h) -> {
      String name = req.getParams().required().get(COLLECTION_PROP);
      // TODO : add support for multiple shards
      String shard = req.getParams().get(SHARD_ID_PROP);
      String rangesStr = req.getParams().get(CoreAdminParams.RANGES);
      String splitKey = req.getParams().get("split.key");
      String numSubShards = req.getParams().get(NUM_SUB_SHARDS);
      String fuzz = req.getParams().get(SPLIT_FUZZ);

      if (splitKey == null && shard == null) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "At least one of shard, or split.key should be specified.");
      }
      if (splitKey != null && shard != null) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "Only one of 'shard' or 'split.key' should be specified");
      }
      if (splitKey != null && rangesStr != null) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "Only one of 'ranges' or 'split.key' should be specified");
      }
      if (numSubShards != null && (splitKey != null || rangesStr != null)) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "numSubShards can not be specified with split.key or ranges parameters");
      }
      if (fuzz != null && (splitKey != null || rangesStr != null)) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "fuzz can not be specified with split.key or ranges parameters");
      }

      Map<String, Object> map = copy(req.getParams(), null,
          COLLECTION_PROP,
          SHARD_ID_PROP,
          "split.key",
          CoreAdminParams.RANGES,
          WAIT_FOR_FINAL_STATE,
          TIMING,
          SPLIT_METHOD,
          NUM_SUB_SHARDS,
          SPLIT_FUZZ,
          SPLIT_BY_PREFIX,
          FOLLOW_ALIASES);
      return copyPropertiesWithPrefix(req.getParams(), map, PROPERTY_PREFIX);
    }),
    DELETESHARD_OP(DELETESHARD, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null,
          COLLECTION_PROP,
          SHARD_ID_PROP);
      copy(req.getParams(), map,
          DELETE_INDEX,
          DELETE_DATA_DIR,
          DELETE_INSTANCE_DIR,
          FOLLOW_ALIASES);
      return map;
    }),
    FORCELEADER_OP(FORCELEADER, (req, rsp, h) -> {
      forceLeaderElection(req, h);
      return null;
    }),
    CREATESHARD_OP(CREATESHARD, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null,
          COLLECTION_PROP,
          SHARD_ID_PROP);
      ClusterState clusterState = h.coreContainer.getZkController().getClusterState();
      final String newShardName = SolrIdentifierValidator.validateShardName(req.getParams().get(SHARD_ID_PROP));
      boolean followAliases = req.getParams().getBool(FOLLOW_ALIASES, false);
      String extCollectionName = req.getParams().get(COLLECTION_PROP);
      String collectionName = followAliases ? h.coreContainer.getZkController().getZkStateReader()
          .getAliases().resolveSimpleAlias(extCollectionName) : extCollectionName;
      if (!ImplicitDocRouter.NAME.equals(((Map<?,?>) clusterState.getCollection(collectionName).get(DOC_ROUTER)).get(NAME)))
        throw new SolrException(ErrorCode.BAD_REQUEST, "shards can be added only to 'implicit' collections");
      copy(req.getParams(), map,
          REPLICATION_FACTOR,
          NRT_REPLICAS,
          TLOG_REPLICAS,
          PULL_REPLICAS,
          CREATE_NODE_SET,
          WAIT_FOR_FINAL_STATE,
          FOLLOW_ALIASES);
      return copyPropertiesWithPrefix(req.getParams(), map, PROPERTY_PREFIX);
    }),
    DELETEREPLICA_OP(DELETEREPLICA, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null,
          COLLECTION_PROP);

      return copy(req.getParams(), map,
          DELETE_INDEX,
          DELETE_DATA_DIR,
          DELETE_INSTANCE_DIR,
          COUNT_PROP, REPLICA_PROP,
          SHARD_ID_PROP,
          ONLY_IF_DOWN,
          FOLLOW_ALIASES);
    }),
    MIGRATE_OP(MIGRATE, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null, COLLECTION_PROP, "split.key", "target.collection");
      return copy(req.getParams(), map, "forward.timeout", FOLLOW_ALIASES);
    }),
    ADDROLE_OP(ADDROLE, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null, "role", "node");
      if (!KNOWN_ROLES.contains(map.get("role")))
        throw new SolrException(ErrorCode.BAD_REQUEST, "Unknown role. Supported roles are ," + KNOWN_ROLES);
      return map;
    }),
    REMOVEROLE_OP(REMOVEROLE, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null, "role", "node");
      if (!KNOWN_ROLES.contains(map.get("role")))
        throw new SolrException(ErrorCode.BAD_REQUEST, "Unknown role. Supported roles are ," + KNOWN_ROLES);
      return map;
    }),
    CLUSTERPROP_OP(CLUSTERPROP, (req, rsp, h) -> {
      String name = req.getParams().required().get(NAME);
      String val = req.getParams().get(VALUE_LONG);
      ClusterProperties cp = new ClusterProperties(h.coreContainer.getZkController().getZkClient());
      cp.setClusterProperty(name, val);
      return null;
    }),
    COLLECTIONPROP_OP(COLLECTIONPROP, (req, rsp, h) -> {
      String extCollection = req.getParams().required().get(NAME);
      String collection = h.coreContainer.getZkController().getZkStateReader().getAliases().resolveSimpleAlias(extCollection);
      String name = req.getParams().required().get(PROPERTY_NAME);
      String val = req.getParams().get(PROPERTY_VALUE);
      CollectionProperties cp = new CollectionProperties(h.coreContainer.getZkController().getZkClient());
      cp.setCollectionProperty(collection, name, val);
      return null;
    }),
    @SuppressWarnings({"unchecked"})
    REQUESTSTATUS_OP(REQUESTSTATUS, (req, rsp, h) -> {
      req.getParams().required().check(REQUESTID);

      final CoreContainer coreContainer = h.coreContainer;
      final String requestId = req.getParams().get(REQUESTID);
      final ZkController zkController = coreContainer.getZkController();

      final NamedList<Object> status = new NamedList<>();
      if (coreContainer.getDistributedCollectionCommandRunner().isEmpty()) {
        if (zkController.getOverseerCompletedMap().contains(requestId)) {
          final byte[] mapEntry = zkController.getOverseerCompletedMap().get(requestId);
          rsp.getValues().addAll(OverseerSolrResponseSerializer.deserialize(mapEntry).getResponse());
          addStatusToResponse(status, COMPLETED, "found [" + requestId + "] in completed tasks");
        } else if (zkController.getOverseerFailureMap().contains(requestId)) {
          final byte[] mapEntry = zkController.getOverseerFailureMap().get(requestId);
          rsp.getValues().addAll(OverseerSolrResponseSerializer.deserialize(mapEntry).getResponse());
          addStatusToResponse(status, FAILED, "found [" + requestId + "] in failed tasks");
        } else if (zkController.getOverseerRunningMap().contains(requestId)) {
          addStatusToResponse(status, RUNNING, "found [" + requestId + "] in running tasks");
        } else if (h.overseerCollectionQueueContains(requestId)) {
          addStatusToResponse(status, SUBMITTED, "found [" + requestId + "] in submitted tasks");
        } else {
          addStatusToResponse(status, NOT_FOUND, "Did not find [" + requestId + "] in any tasks queue");
        }
      } else {
        Pair<RequestStatusState, OverseerSolrResponse> sr =
            coreContainer.getDistributedCollectionCommandRunner().get().getAsyncTaskRequestStatus(requestId);
        final String message;
        switch (sr.first()) {
          case COMPLETED:
            message = "found [" + requestId + "] in completed tasks";
            rsp.getValues().addAll(sr.second().getResponse());
            break;
          case FAILED:
            message = "found [" + requestId + "] in failed tasks";
            rsp.getValues().addAll(sr.second().getResponse());
            break;
          case RUNNING:
            message = "found [" + requestId + "] in running tasks";
            break;
          case SUBMITTED:
            message = "found [" + requestId + "] in submitted tasks";
            break;
          default:
            message = "Did not find [" + requestId + "] in any tasks queue";
        }
        addStatusToResponse(status, sr.first(), message);
      }

      rsp.getValues().addAll(status);
      return null;
    }),
    DELETESTATUS_OP(DELETESTATUS, new CollectionOp() {
      @SuppressWarnings("unchecked")
      @Override
      public Map<String, Object> execute(SolrQueryRequest req, SolrQueryResponse rsp, CollectionsHandler h) throws Exception {
        final CoreContainer coreContainer = h.coreContainer;
        final String requestId = req.getParams().get(REQUESTID);
        final ZkController zkController = coreContainer.getZkController();
        Boolean flush = req.getParams().getBool(CollectionAdminParams.FLUSH, false);

        if (requestId == null && !flush) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "Either requestid or flush parameter must be specified.");
        }

        if (requestId != null && flush) {
          throw new SolrException(ErrorCode.BAD_REQUEST,
              "Both requestid and flush parameters can not be specified together.");
        }

        if (coreContainer.getDistributedCollectionCommandRunner().isEmpty()) {
          if (flush) {
            Collection<String> completed = zkController.getOverseerCompletedMap().keys();
            Collection<String> failed = zkController.getOverseerFailureMap().keys();
            for (String asyncId : completed) {
              zkController.getOverseerCompletedMap().remove(asyncId);
              zkController.clearAsyncId(asyncId);
            }
            for (String asyncId : failed) {
              zkController.getOverseerFailureMap().remove(asyncId);
              zkController.clearAsyncId(asyncId);
            }
            rsp.getValues().add("status", "successfully cleared stored collection api responses");
          } else {
            // Request to cleanup
            if (zkController.getOverseerCompletedMap().remove(requestId)) {
              zkController.clearAsyncId(requestId);
              rsp.getValues().add("status", "successfully removed stored response for [" + requestId + "]");
            } else if (zkController.getOverseerFailureMap().remove(requestId)) {
              zkController.clearAsyncId(requestId);
              rsp.getValues().add("status", "successfully removed stored response for [" + requestId + "]");
            } else {
              rsp.getValues().add("status", "[" + requestId + "] not found in stored responses");
              // Don't call zkController.clearAsyncId for this, since it could be a running/pending task
            }
          }
        } else {
          if (flush) {
            coreContainer.getDistributedCollectionCommandRunner().get().deleteAllAsyncIds();
            rsp.getValues().add("status", "successfully cleared stored collection api responses");
          } else {
            if (coreContainer.getDistributedCollectionCommandRunner().get().deleteSingleAsyncId(requestId)) {
              rsp.getValues().add("status", "successfully removed stored response for [" + requestId + "]");
            } else {
              rsp.getValues().add("status", "[" + requestId + "] not found in stored responses");
            }
          }
        }
        return null;
      }
    }),
    ADDREPLICA_OP(ADDREPLICA, (req, rsp, h) -> {
      Map<String, Object> props = copy(req.getParams(), null,
          COLLECTION_PROP,
          "node",
          SHARD_ID_PROP,
          _ROUTE_,
          CoreAdminParams.NAME,
          INSTANCE_DIR,
          DATA_DIR,
          ULOG_DIR,
          REPLICA_TYPE,
          WAIT_FOR_FINAL_STATE,
          NRT_REPLICAS,
          TLOG_REPLICAS,
          PULL_REPLICAS,
          CREATE_NODE_SET,
          FOLLOW_ALIASES,
          SKIP_NODE_ASSIGNMENT);
      return copyPropertiesWithPrefix(req.getParams(), props, PROPERTY_PREFIX);
    }),
    OVERSEERSTATUS_OP(OVERSEERSTATUS, (req, rsp, h) -> new LinkedHashMap<>()),
    @SuppressWarnings({"unchecked"})
    DISTRIBUTEDAPIPROCESSING_OP(DISTRIBUTEDAPIPROCESSING, (req, rsp, h)  -> {
      NamedList<Object> results = new NamedList<>();
      boolean isDistributedApi = h.coreContainer.getDistributedCollectionCommandRunner().isPresent();
      results.add("isDistributedApi", isDistributedApi);
      rsp.getValues().addAll(results);
      return null;
    }),
    /**
     * Handle list collection request.
     * Do list collection request to zk host
     */
    @SuppressWarnings({"unchecked"})
    LIST_OP(LIST, (req, rsp, h) -> {
      NamedList<Object> results = new NamedList<>();
      Map<String, DocCollection> collections = h.coreContainer.getZkController().getZkStateReader().getClusterState().getCollectionsMap();
      List<String> collectionList = new ArrayList<>(collections.keySet());
      Collections.sort(collectionList);
      // XXX should we add aliases here?
      results.add("collections", collectionList);
      SolrResponse response = new OverseerSolrResponse(results);
      rsp.getValues().addAll(response.getResponse());
      return null;
    }),
    /**
     * Handle cluster status request.
     * Can return status per specific collection/shard or per all collections.
     */
    CLUSTERSTATUS_OP(CLUSTERSTATUS, (req, rsp, h) -> {
      Map<String, Object> all = copy(req.getParams(), null,
          COLLECTION_PROP,
          SHARD_ID_PROP,
          _ROUTE_);
      new ClusterStatus(h.coreContainer.getZkController().getZkStateReader(),
          new ZkNodeProps(all)).getClusterStatus(rsp.getValues());
      return null;
    }),
    ADDREPLICAPROP_OP(ADDREPLICAPROP, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null,
          COLLECTION_PROP,
          PROPERTY_PROP,
          SHARD_ID_PROP,
          REPLICA_PROP,
          PROPERTY_VALUE_PROP);
      copy(req.getParams(), map, SHARD_UNIQUE);
      String property = (String) map.get(PROPERTY_PROP);
      if (!property.startsWith(PROPERTY_PREFIX)) {
        property = PROPERTY_PREFIX + property;
      }

      boolean uniquePerSlice = Boolean.parseBoolean((String) map.get(SHARD_UNIQUE));

      // Check if we're trying to set a property with parameters that allow us to set the property on multiple replicas
      // in a slice on properties that are known to only be one-per-slice and error out if so.
      if (StringUtils.isNotBlank((String) map.get(SHARD_UNIQUE)) &&
          SliceMutator.SLICE_UNIQUE_BOOLEAN_PROPERTIES.contains(property.toLowerCase(Locale.ROOT)) &&
          uniquePerSlice == false) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "Overseer replica property command received for property " + property +
                " with the " + SHARD_UNIQUE +
                " parameter set to something other than 'true'. No action taken.");
      }
      return map;
    }),
    // XXX should this command support followAliases?
    DELETEREPLICAPROP_OP(DELETEREPLICAPROP, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null,
          COLLECTION_PROP,
          PROPERTY_PROP,
          SHARD_ID_PROP,
          REPLICA_PROP);
      return copy(req.getParams(), map, PROPERTY_PROP);
    }),
    // XXX should this command support followAliases?
    BALANCESHARDUNIQUE_OP(BALANCESHARDUNIQUE, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null,
          COLLECTION_PROP,
          PROPERTY_PROP);
      Boolean shardUnique = Boolean.parseBoolean(req.getParams().get(SHARD_UNIQUE));
      String prop = req.getParams().get(PROPERTY_PROP).toLowerCase(Locale.ROOT);
      if (!StringUtils.startsWith(prop, PROPERTY_PREFIX)) {
        prop = PROPERTY_PREFIX + prop;
      }

      if (!shardUnique && !SliceMutator.SLICE_UNIQUE_BOOLEAN_PROPERTIES.contains(prop)) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Balancing properties amongst replicas in a slice requires that"
            + " the property be pre-defined as a unique property (e.g. 'preferredLeader') or that 'shardUnique' be set to 'true'. " +
            " Property: " + prop + " shardUnique: " + shardUnique);
      }

      return copy(req.getParams(), map, ONLY_ACTIVE_NODES, SHARD_UNIQUE);
    }),
    REBALANCELEADERS_OP(REBALANCELEADERS, (req, rsp, h) -> {
      new RebalanceLeaders(req, rsp, h).execute();
      return null;
    }),
    // XXX should this command support followAliases?
    MODIFYCOLLECTION_OP(MODIFYCOLLECTION, (req, rsp, h) -> {
      Map<String, Object> m = copy(req.getParams(), null, CollectionAdminRequest.MODIFIABLE_COLLECTION_PROPERTIES);
      copyPropertiesWithPrefix(req.getParams(), m, PROPERTY_PREFIX);
      if (m.isEmpty()) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            formatString("no supported values provided {0}", CollectionAdminRequest.MODIFIABLE_COLLECTION_PROPERTIES.toString()));
      }
      copy(req.getParams().required(), m, COLLECTION_PROP);
      for (Map.Entry<String, Object> entry : m.entrySet()) {
        String prop = entry.getKey();
        if ("".equals(entry.getValue())) {
          // set to an empty string is equivalent to removing the property, see SOLR-12507
          entry.setValue(null);
        }
        DocCollection.verifyProp(m, prop);
      }
      if (m.get(REPLICATION_FACTOR) != null) {
        m.put(NRT_REPLICAS, m.get(REPLICATION_FACTOR));
      }
      return m;
    }),
    BACKUP_OP(BACKUP, (req, rsp, h) -> {
      req.getParams().required().check(NAME, COLLECTION_PROP);

      final String extCollectionName = req.getParams().get(COLLECTION_PROP);
      final boolean followAliases = req.getParams().getBool(FOLLOW_ALIASES, false);
      final String collectionName = followAliases ? h.coreContainer.getZkController().getZkStateReader()
          .getAliases().resolveSimpleAlias(extCollectionName) : extCollectionName;
      final ClusterState clusterState = h.coreContainer.getZkController().getClusterState();
      if (!clusterState.hasCollection(collectionName)) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Collection '" + collectionName + "' does not exist, no action taken.");
      }

      CoreContainer cc = h.coreContainer;
      String repo = req.getParams().get(CoreAdminParams.BACKUP_REPOSITORY);
      BackupRepository repository = cc.newBackupRepository(repo);

      String location = repository.getBackupLocation(req.getParams().get(CoreAdminParams.BACKUP_LOCATION));
      if (location == null) {
        //Refresh the cluster property file to make sure the value set for location is the latest
        // Check if the location is specified in the cluster property.
        location = new ClusterProperties(h.coreContainer.getZkController().getZkClient()).getClusterProperty(CoreAdminParams.BACKUP_LOCATION, null);
        if (location == null) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "'location' is not specified as a query"
              + " parameter or as a default repository property or as a cluster property.");
        }
      }
      boolean incremental = req.getParams().getBool(CoreAdminParams.BACKUP_INCREMENTAL, true);

      // Check if the specified location is valid for this repository.
      final URI uri = repository.createDirectoryURI(location);
      try {
        if (!repository.exists(uri)) {
          throw new SolrException(ErrorCode.SERVER_ERROR, "specified location " + uri + " does not exist.");
        }
      } catch (IOException ex) {
        throw new SolrException(ErrorCode.SERVER_ERROR, "Failed to check the existence of " + uri + ". Is it valid?", ex);
      }

      String strategy = req.getParams().get(CollectionAdminParams.INDEX_BACKUP_STRATEGY, CollectionAdminParams.COPY_FILES_STRATEGY);
      if (!CollectionAdminParams.INDEX_BACKUP_STRATEGIES.contains(strategy)) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Unknown index backup strategy " + strategy);
      }

      Map<String, Object> params = copy(req.getParams(), null, NAME, COLLECTION_PROP,
              FOLLOW_ALIASES, CoreAdminParams.COMMIT_NAME, CoreAdminParams.MAX_NUM_BACKUP_POINTS);
      params.put(CoreAdminParams.BACKUP_LOCATION, location);
      if (repo != null) {
        params.put(CoreAdminParams.BACKUP_REPOSITORY, repo);
      }

      params.put(CollectionAdminParams.INDEX_BACKUP_STRATEGY, strategy);
      params.put(CoreAdminParams.BACKUP_INCREMENTAL, incremental);
      return params;
    }),
    RESTORE_OP(RESTORE, (req, rsp, h) -> {
      req.getParams().required().check(NAME, COLLECTION_PROP);

      final String collectionName = SolrIdentifierValidator.validateCollectionName(req.getParams().get(COLLECTION_PROP));
      if (h.coreContainer.getZkController().getZkStateReader().getAliases().hasAlias(collectionName)) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Collection '" + collectionName + "' is an existing alias, no action taken.");
      }

      final CoreContainer cc = h.coreContainer;
      final String repo = req.getParams().get(CoreAdminParams.BACKUP_REPOSITORY);
      final BackupRepository repository = cc.newBackupRepository(repo);

      String location = repository.getBackupLocation(req.getParams().get(CoreAdminParams.BACKUP_LOCATION));
      if (location == null) {
        //Refresh the cluster property file to make sure the value set for location is the latest
        // Check if the location is specified in the cluster property.
        location = new ClusterProperties(h.coreContainer.getZkController().getZkClient()).getClusterProperty("location", null);
        if (location == null) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "'location' is not specified as a query"
              + " parameter or as a default repository property or as a cluster property.");
        }
      }

      // Check if the specified location is valid for this repository.
      final URI uri = repository.createDirectoryURI(location);
      try {
        if (!repository.exists(uri)) {
          throw new SolrException(ErrorCode.SERVER_ERROR, "specified location " + uri + " does not exist.");
        }
      } catch (IOException ex) {
        throw new SolrException(ErrorCode.SERVER_ERROR, "Failed to check the existence of " + uri + ". Is it valid?", ex);
      }

      final String createNodeArg = req.getParams().get(CREATE_NODE_SET);
      if (CREATE_NODE_SET_EMPTY.equals(createNodeArg)) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            "Cannot restore with a CREATE_NODE_SET of CREATE_NODE_SET_EMPTY."
        );
      }
      if (req.getParams().get(NRT_REPLICAS) != null && req.getParams().get(REPLICATION_FACTOR) != null) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            "Cannot set both replicationFactor and nrtReplicas as they mean the same thing");
      }

      final Map<String, Object> params = copy(req.getParams(), null, NAME, COLLECTION_PROP);
      params.put(CoreAdminParams.BACKUP_LOCATION, location);
      if (repo != null) {
        params.put(CoreAdminParams.BACKUP_REPOSITORY, repo);
      }
      // from CREATE_OP:
      copy(req.getParams(), params, COLL_CONF, REPLICATION_FACTOR, NRT_REPLICAS, TLOG_REPLICAS,
          PULL_REPLICAS, CREATE_NODE_SET, CREATE_NODE_SET_SHUFFLE, BACKUP_ID);
      copyPropertiesWithPrefix(req.getParams(), params, PROPERTY_PREFIX);
      return params;
    }),
    DELETEBACKUP_OP(DELETEBACKUP, (req, rsp, h) -> {
      req.getParams().required().check(NAME);

      CoreContainer cc = h.coreContainer;
      String repo = req.getParams().get(CoreAdminParams.BACKUP_REPOSITORY);
      try (BackupRepository repository = cc.newBackupRepository(repo)) {

        String location = repository.getBackupLocation(req.getParams().get(CoreAdminParams.BACKUP_LOCATION));
        if (location == null) {
          //Refresh the cluster property file to make sure the value set for location is the latest
          // Check if the location is specified in the cluster property.
          location = new ClusterProperties(h.coreContainer.getZkController().getZkClient()).getClusterProperty("location", null);
          if (location == null) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "'location' is not specified as a query"
                    + " parameter or as a default repository property or as a cluster property.");
          }
        }

        // Check if the specified location is valid for this repository.
        URI uri = repository.createDirectoryURI(location);
        try {
          if (!repository.exists(uri)) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "specified location " + uri + " does not exist.");
          }
        } catch (IOException ex) {
          throw new SolrException(ErrorCode.SERVER_ERROR, "Failed to check the existence of " + uri + ". Is it valid?", ex);
        }

        int deletionModesProvided = 0;
        if (req.getParams().get(MAX_NUM_BACKUP_POINTS) != null) deletionModesProvided++;
        if (req.getParams().get(BACKUP_PURGE_UNUSED) != null) deletionModesProvided++;
        if (req.getParams().get(BACKUP_ID) != null) deletionModesProvided++;
        if (deletionModesProvided != 1) {
          throw new SolrException(BAD_REQUEST,
                  String.format(Locale.ROOT, "Exactly one of %s, %s, and %s parameters must be provided",
                          MAX_NUM_BACKUP_POINTS, BACKUP_PURGE_UNUSED, BACKUP_ID));
        }

        final Map<String, Object> params = copy(req.getParams(), null, NAME, BACKUP_REPOSITORY,
                BACKUP_LOCATION, BACKUP_ID, MAX_NUM_BACKUP_POINTS, BACKUP_PURGE_UNUSED);
        params.put(BACKUP_LOCATION, location);
        if (repo != null) {
          params.put(CoreAdminParams.BACKUP_REPOSITORY, repo);
        }
        return params;
      }
    }),
    LISTBACKUP_OP(LISTBACKUP, (req, rsp, h) -> {
      req.getParams().required().check(NAME);

      CoreContainer cc = h.coreContainer;
      String repo = req.getParams().get(CoreAdminParams.BACKUP_REPOSITORY);
      try (BackupRepository repository = cc.newBackupRepository(repo)) {

        String location = repository.getBackupLocation(req.getParams().get(CoreAdminParams.BACKUP_LOCATION));
        if (location == null) {
          //Refresh the cluster property file to make sure the value set for location is the latest
          // Check if the location is specified in the cluster property.
          location = new ClusterProperties(h.coreContainer.getZkController().getZkClient()).getClusterProperty(CoreAdminParams.BACKUP_LOCATION, null);
          if (location == null) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "'location' is not specified as a query"
                    + " parameter or as a default repository property or as a cluster property.");
          }
        }

        String backupName = req.getParams().get(NAME);
        final URI locationURI = repository.createDirectoryURI(location);
        try {
          if (!repository.exists(locationURI)) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "specified location " + locationURI + " does not exist.");
          }
        } catch (IOException ex) {
          throw new SolrException(ErrorCode.SERVER_ERROR, "Failed to check the existence of " + locationURI + ". Is it valid?", ex);
        }
        URI backupLocation = BackupFilePaths.buildExistingBackupLocationURI(repository, locationURI, backupName);
        if (repository.exists(repository.resolve(backupLocation, BackupManager.TRADITIONAL_BACKUP_PROPS_FILE))) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "The backup name [" + backupName + "] at " +
                  "location [" + location + "] holds a non-incremental (legacy) backup, but " +
                  "backup-listing is only supported on incremental backups");
        }

        String[] subFiles = repository.listAllOrEmpty(backupLocation);
        List<BackupId> propsFiles = BackupFilePaths.findAllBackupIdsFromFileListing(subFiles);

        NamedList<Object> results = new NamedList<>();
        ArrayList<Map<Object,Object>> backups = new ArrayList<>();
        String collectionName = null;
        for (BackupId backupId: propsFiles) {
          BackupProperties properties = BackupProperties.readFrom(repository, backupLocation, BackupFilePaths.getBackupPropsName(backupId));
          if (collectionName == null) {
            collectionName = properties.getCollection();
            results.add(BackupManager.COLLECTION_NAME_PROP, collectionName);
          }

          Map<Object, Object> details = properties.getDetails();
          details.put("backupId", backupId.id);
          backups.add(details);
        }

        results.add("backups", backups);
        SolrResponse response = new OverseerSolrResponse(results);
        rsp.getValues().addAll(response.getResponse());
        return null;
      }
    }),
    CREATESNAPSHOT_OP(CREATESNAPSHOT, (req, rsp, h) -> {
      req.getParams().required().check(COLLECTION_PROP, CoreAdminParams.COMMIT_NAME);

      String extCollectionName = req.getParams().get(COLLECTION_PROP);
      boolean followAliases = req.getParams().getBool(FOLLOW_ALIASES, false);
      String collectionName = followAliases ? h.coreContainer.getZkController().getZkStateReader()
          .getAliases().resolveSimpleAlias(extCollectionName) : extCollectionName;
      String commitName = req.getParams().get(CoreAdminParams.COMMIT_NAME);
      ClusterState clusterState = h.coreContainer.getZkController().getClusterState();
      if (!clusterState.hasCollection(collectionName)) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Collection '" + collectionName + "' does not exist, no action taken.");
      }

      SolrZkClient client = h.coreContainer.getZkController().getZkClient();
      if (SolrSnapshotManager.snapshotExists(client, collectionName, commitName)) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "Snapshot with name '" + commitName + "' already exists for collection '"
                + collectionName + "', no action taken.");
      }

      Map<String, Object> params = copy(req.getParams(), null, COLLECTION_PROP, FOLLOW_ALIASES, CoreAdminParams.COMMIT_NAME);
      return params;
    }),
    DELETESNAPSHOT_OP(DELETESNAPSHOT, (req, rsp, h) -> {
      req.getParams().required().check(COLLECTION_PROP, CoreAdminParams.COMMIT_NAME);

      String extCollectionName = req.getParams().get(COLLECTION_PROP);
      String collectionName = h.coreContainer.getZkController().getZkStateReader()
          .getAliases().resolveSimpleAlias(extCollectionName);
      ClusterState clusterState = h.coreContainer.getZkController().getClusterState();
      if (!clusterState.hasCollection(collectionName)) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Collection '" + collectionName + "' does not exist, no action taken.");
      }

      Map<String, Object> params = copy(req.getParams(), null, COLLECTION_PROP, FOLLOW_ALIASES, CoreAdminParams.COMMIT_NAME);
      return params;
    }),
    LISTSNAPSHOTS_OP(LISTSNAPSHOTS, (req, rsp, h) -> {
      req.getParams().required().check(COLLECTION_PROP);

      String extCollectionName = req.getParams().get(COLLECTION_PROP);
      String collectionName = h.coreContainer.getZkController().getZkStateReader()
          .getAliases().resolveSimpleAlias(extCollectionName);
      ClusterState clusterState = h.coreContainer.getZkController().getClusterState();
      if (!clusterState.hasCollection(collectionName)) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Collection '" + collectionName + "' does not exist, no action taken.");
      }

      NamedList<Object> snapshots = new NamedList<Object>();
      SolrZkClient client = h.coreContainer.getZkController().getZkClient();
      Collection<CollectionSnapshotMetaData> m = SolrSnapshotManager.listSnapshots(client, collectionName);
      for (CollectionSnapshotMetaData meta : m) {
        snapshots.add(meta.getName(), meta.toNamedList());
      }

      rsp.add(SolrSnapshotManager.SNAPSHOTS_INFO, snapshots);
      return null;
    }),
    REPLACENODE_OP(REPLACENODE, (req, rsp, h) -> {
      return copy(req.getParams(), null,
          "source", //legacy
          "target",//legacy
          WAIT_FOR_FINAL_STATE,
          CollectionParams.SOURCE_NODE,
          CollectionParams.TARGET_NODE);
    }),
    MOVEREPLICA_OP(MOVEREPLICA, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null,
          COLLECTION_PROP);

      return copy(req.getParams(), map,
          CollectionParams.FROM_NODE,
          CollectionParams.SOURCE_NODE,
          CollectionParams.TARGET_NODE,
          WAIT_FOR_FINAL_STATE,
          IN_PLACE_MOVE,
          "replica",
          "shard",
          FOLLOW_ALIASES);
    }),
    DELETENODE_OP(DELETENODE, (req, rsp, h) -> copy(req.getParams().required(), null, "node")),
    MOCK_COLL_TASK_OP(MOCK_COLL_TASK, (req, rsp, h) -> {
      Map<String, Object> map = copy(req.getParams().required(), null, COLLECTION_PROP);
      return copy(req.getParams(), map, "sleep");});

    /**
     * Places all prefixed properties in the sink map (or a new map) using the prefix as the key and a map of
     * all prefixed properties as the value. The sub-map keys have the prefix removed.
     *
     * @param params The solr params from which to extract prefixed properties.
     * @param sink   The map to add the properties too.
     * @param prefix The prefix to identify properties to be extracted
     * @return The sink map, or a new map if the sink map was null
     */
    private static Map<String, Object> convertPrefixToMap(SolrParams params, Map<String, Object> sink, String prefix) {
      Map<String, Object> result = new LinkedHashMap<>();
      Iterator<String> iter = params.getParameterNamesIterator();
      while (iter.hasNext()) {
        String param = iter.next();
        if (param.startsWith(prefix)) {
          result.put(param.substring(prefix.length() + 1), params.get(param));
        }
      }
      if (sink == null) {
        sink = new LinkedHashMap<>();
      }
      sink.put(prefix, result);
      return sink;
    }

    public final CollectionOp fun;
    CollectionAction action;
    long timeOut;

    CollectionOperation(CollectionAction action, CollectionOp fun) {
      this(action, DEFAULT_COLLECTION_OP_TIMEOUT, fun);
    }

    CollectionOperation(CollectionAction action, long timeOut, CollectionOp fun) {
      this.action = action;
      this.timeOut = timeOut;
      this.fun = fun;

    }

    public static CollectionOperation get(CollectionAction action) {
      for (CollectionOperation op : values()) {
        if (op.action == action) return op;
      }
      throw new SolrException(ErrorCode.SERVER_ERROR, "No such action " + action);
    }

    @Override
    public Map<String, Object> execute(SolrQueryRequest req, SolrQueryResponse rsp, CollectionsHandler h)
        throws Exception {
      return fun.execute(req, rsp, h);
    }
  }

  private static void forceLeaderElection(SolrQueryRequest req, CollectionsHandler handler) {
    ZkController zkController = handler.coreContainer.getZkController();
    ClusterState clusterState = zkController.getClusterState();
    String extCollectionName = req.getParams().required().get(COLLECTION_PROP);
    String collectionName = zkController.zkStateReader.getAliases().resolveSimpleAlias(extCollectionName);
    String sliceId = req.getParams().required().get(SHARD_ID_PROP);

    log.info("Force leader invoked, state: {}", clusterState);
    DocCollection collection = clusterState.getCollection(collectionName);
    Slice slice = collection.getSlice(sliceId);
    if (slice == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "No shard with name " + sliceId + " exists for collection " + collectionName);
    }

    try (ZkShardTerms zkShardTerms = new ZkShardTerms(collectionName, slice.getName(), zkController.getZkClient())) {
      // if an active replica is the leader, then all is fine already
      Replica leader = slice.getLeader();
      if (leader != null && leader.getState() == State.ACTIVE) {
        throw new SolrException(ErrorCode.SERVER_ERROR,
            "The shard already has an active leader. Force leader is not applicable. State: " + slice);
      }

      final Set<String> liveNodes = clusterState.getLiveNodes();
      List<Replica> liveReplicas = slice.getReplicas().stream()
          .filter(rep -> liveNodes.contains(rep.getNodeName())).collect(Collectors.toList());
      boolean shouldIncreaseReplicaTerms = liveReplicas.stream()
          .noneMatch(rep -> zkShardTerms.registered(rep.getName()) && zkShardTerms.canBecomeLeader(rep.getName()));
      // we won't increase replica's terms if exist a live replica with term equals to leader
      if (shouldIncreaseReplicaTerms) {
        //TODO only increase terms of replicas less out-of-sync
        liveReplicas.stream()
            .filter(rep -> zkShardTerms.registered(rep.getName()))
            // TODO should this all be done at once instead of increasing each replica individually?
            .forEach(rep -> zkShardTerms.setTermEqualsToLeader(rep.getName()));
      }

      // Wait till we have an active leader
      boolean success = false;
      for (int i = 0; i < 9; i++) {
        Thread.sleep(5000);
        clusterState = handler.coreContainer.getZkController().getClusterState();
        collection = clusterState.getCollection(collectionName);
        slice = collection.getSlice(sliceId);
        if (slice.getLeader() != null && slice.getLeader().getState() == State.ACTIVE) {
          success = true;
          break;
        }
        log.warn("Force leader attempt {}. Waiting 5 secs for an active leader. State of the slice: {}", (i + 1), slice); //nowarn
      }

      if (success) {
        log.info("Successfully issued FORCELEADER command for collection: {}, shard: {}", collectionName, sliceId);
      } else {
        log.info("Couldn't successfully force leader, collection: {}, shard: {}. Cluster state: {}", collectionName, sliceId, clusterState);
      }
    } catch (SolrException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(ErrorCode.SERVER_ERROR,
          "Error executing FORCELEADER operation for collection: " + collectionName + " shard: " + sliceId, e);
    }
  }

  public static void waitForActiveCollection(String collectionName, CoreContainer cc, SolrResponse createCollResponse)
      throws KeeperException, InterruptedException {

    if (createCollResponse.getResponse().get("exception") != null) {
      // the main called failed, don't wait
      if (log.isInfoEnabled()) {
        log.info("Not waiting for active collection due to exception: {}", createCollResponse.getResponse().get("exception"));
      }
      return;
    }

    int replicaFailCount;
    if (createCollResponse.getResponse().get("failure") != null) {
      replicaFailCount = ((NamedList) createCollResponse.getResponse().get("failure")).size();
    } else {
      replicaFailCount = 0;
    }

    CloudConfig ccfg = cc.getConfig().getCloudConfig();
    Integer seconds = ccfg.getCreateCollectionWaitTimeTillActive();
    Boolean checkLeaderOnly = ccfg.isCreateCollectionCheckLeaderActive();
    if (log.isInfoEnabled()) {
      log.info("Wait for new collection to be active for at most {} seconds. Check all shard {}"
          , seconds, (checkLeaderOnly ? "leaders" : "replicas"));
    }

    try {
      cc.getZkController().getZkStateReader().waitForState(collectionName, seconds, TimeUnit.SECONDS, (n, c) -> {

        if (c == null) {
          // the collection was not created, don't wait
          return true;
        }

        if (c.getSlices() != null) {
          Collection<Slice> shards = c.getSlices();
          int replicaNotAliveCnt = 0;
          for (Slice shard : shards) {
            Collection<Replica> replicas;
            if (!checkLeaderOnly) replicas = shard.getReplicas();
            else {
              replicas = new ArrayList<Replica>();
              replicas.add(shard.getLeader());
            }
            for (Replica replica : replicas) {
              String state = replica.getStr(ZkStateReader.STATE_PROP);
              if (log.isDebugEnabled()) {
                log.debug("Checking replica status, collection={} replica={} state={}", collectionName,
                    replica.getCoreUrl(), state);
              }
              if (!n.contains(replica.getNodeName())
                  || !state.equals(Replica.State.ACTIVE.toString())) {
                replicaNotAliveCnt++;
                return false;
              }
            }
          }

          return (replicaNotAliveCnt == 0) || (replicaNotAliveCnt <= replicaFailCount);
        }
        return false;
      });
    } catch (TimeoutException | InterruptedException e) {

      String error = "Timeout waiting for active collection " + collectionName + " with timeout=" + seconds;
      throw new NotInClusterStateException(ErrorCode.SERVER_ERROR, error);
    }

  }


  private static void verifyShardsParam(String shardsParam) {
    for (String shard : shardsParam.split(",")) {
      SolrIdentifierValidator.validateShardName(shard);
    }
  }

  interface CollectionOp {
    Map<String, Object> execute(SolrQueryRequest req, SolrQueryResponse rsp, CollectionsHandler h) throws Exception;

  }

  @Override
  public Boolean registerV2() {
    return Boolean.TRUE;
  }

  // These "copy" methods were once SolrParams.getAll but were moved here as there is no universal way that
  //  a SolrParams can be represented in a Map; there are various choices.

  /**
   * Copy all params to the given map or if the given map is null create a new one
   */
  static Map<String, Object> copy(SolrParams source, Map<String, Object> sink, Collection<String> paramNames) {
    if (sink == null) sink = new LinkedHashMap<>();
    for (String param : paramNames) {
      String[] v = source.getParams(param);
      if (v != null && v.length > 0) {
        if (v.length == 1) {
          sink.put(param, v[0]);
        } else {
          sink.put(param, v);
        }
      }
    }
    return sink;
  }

  /**
   * Copy all params to the given map or if the given map is null create a new one
   */
  static Map<String, Object> copy(SolrParams source, Map<String, Object> sink, String... paramNames) {
    return copy(source, sink, paramNames == null ? Collections.emptyList() : Arrays.asList(paramNames));
  }

}
