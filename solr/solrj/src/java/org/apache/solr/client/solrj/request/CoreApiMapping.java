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

package org.apache.solr.client.solrj.request;


import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.ApiMapping.CommandMeta;
import org.apache.solr.common.params.CoreAdminParams.CoreAdminAction;

import java.util.Collections;
import java.util.Map;

import static org.apache.solr.client.solrj.SolrRequest.METHOD.GET;
import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;
import static org.apache.solr.client.solrj.request.CoreApiMapping.EndPoint.*;

/** stores the mapping of v1 API parameters to v2 API parameters
 * for core admin API
 *
 */
public class CoreApiMapping {
  public enum Meta implements CommandMeta {
    CREATE(CORES_COMMANDS, POST, CoreAdminAction.CREATE, "create", Collections.singletonMap("config", "configSet")),
    UNLOAD(PER_CORE_COMMANDS, POST, CoreAdminAction.UNLOAD, "unload", null),
    RELOAD(PER_CORE_COMMANDS, POST, CoreAdminAction.RELOAD, "reload", null),
    STATUS(CORES_STATUS, GET, CoreAdminAction.STATUS, "status", null),
    SWAP(PER_CORE_COMMANDS, POST, CoreAdminAction.SWAP, "swap", Collections.singletonMap("other", "with")),
    RENAME(PER_CORE_COMMANDS, POST, CoreAdminAction.RENAME, "rename", Collections.singletonMap("other", "to")),
    MERGEINDEXES(PER_CORE_COMMANDS, POST, CoreAdminAction.MERGEINDEXES, "merge-indexes", null),
    SPLIT(PER_CORE_COMMANDS, POST, CoreAdminAction.SPLIT, "split", Collections.singletonMap("split.key", "splitKey")),
    PREPRECOVERY(PER_CORE_COMMANDS, POST, CoreAdminAction.PREPRECOVERY, "prep-recovery", null),
    REQUESTRECOVERY(PER_CORE_COMMANDS, POST, CoreAdminAction.REQUESTRECOVERY, "request-recovery", null),
    REQUESTSYNCSHARD(PER_CORE_COMMANDS, POST, CoreAdminAction.REQUESTSYNCSHARD, "request-sync-shard", null),
    REQUESTBUFFERUPDATES(PER_CORE_COMMANDS, POST, CoreAdminAction.REQUESTBUFFERUPDATES, "request-buffer-updates", null),
    REQUESTAPPLYUPDATES(PER_CORE_COMMANDS, POST, CoreAdminAction.REQUESTAPPLYUPDATES, "request-apply-updates", null),
    REQUESTSTATUS(PER_CORE_COMMANDS, GET, CoreAdminAction.REQUESTSTATUS, "request-status", null)/*TODO*/;

    public final String commandName;
    public final EndPoint endPoint;
    public final SolrRequest.METHOD method;
    public final CoreAdminAction action;
    public final Map<String, String> paramstoAttr;

    Meta(EndPoint endPoint, SolrRequest.METHOD method, CoreAdminAction action, String commandName,
         Map<String,String> paramstoAttr) {
      this.commandName = commandName;
      this.endPoint = endPoint;
      this.method = method;
      this.paramstoAttr = paramstoAttr == null ? Collections.emptyMap() : paramstoAttr; // expect this to be immutable
      this.action = action;
    }

    @Override
    public String getName() {
      return commandName;
    }

    @Override
    public SolrRequest.METHOD getHttpMethod() {
      return method;
    }

    @Override
    public ApiMapping.V2EndPoint getEndPoint() {
      return endPoint;
    }

    @Override
    public String getParamSubstitute(String param) {
      return paramstoAttr.containsKey(param) ? paramstoAttr.get(param) : param;
    }
  }

  public enum EndPoint implements ApiMapping.V2EndPoint {
    CORES_STATUS("cores.Status"),
    CORES_COMMANDS("cores.Commands"),
    PER_CORE_COMMANDS("cores.core.Commands");

    final String specName;

    EndPoint(String specName) {
      this.specName = specName;
    }

    @Override
    public String getSpecName() {
      return specName;
    }
  }
}
