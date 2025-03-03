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
package org.apache.solr;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.ContentStreamBase.ByteArrayStream;
import org.junit.After;
import org.junit.AfterClass;

import com.google.common.io.ByteStreams;

abstract public class EmbeddedSolrServerTestBase extends SolrTestCaseJ4 {

  protected static final String DEFAULT_CORE_NAME = "collection1";

  public static EmbeddedSolrServer client = null;

  @After
  public synchronized void afterClass() throws Exception {
    if (client != null) client.close();
    client = null;
  }

  @AfterClass
  public static void afterEmbeddedSolrServerTestBase() throws Exception {

  }

  public synchronized EmbeddedSolrServer getSolrClient() {
    if (client == null) {
      client = createNewSolrClient();
    }
    return client;
  }

  /**
   * Create a new solr client. Subclasses should override for other options.
   */
  public EmbeddedSolrServer createNewSolrClient() {
    return new EmbeddedSolrServer(h.getCoreContainer(), DEFAULT_CORE_NAME);
  }

  public void upload(final String collection, final ContentStream... contents) {
    final Path base = Paths.get(getSolrClient().getCoreContainer().getSolrHome(), collection);
    writeTo(base, contents);
  }

  private void writeTo(final Path base, final ContentStream... contents) {
    try {
      if (!Files.exists(base)) {
        Files.createDirectories(base);
      }

      for (final ContentStream content : contents) {
        final File file = new File(base.toFile(), content.getName());
        file.getParentFile().mkdirs();

        try (OutputStream os = new FileOutputStream(file)) {
          ByteStreams.copy(content.getStream(), os);
        }
      }
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  public Collection<ContentStream> download(final String collection, final String... names) {
    final Path base = Paths.get(getSolrClient().getCoreContainer().getSolrHome(), collection);
    final List<ContentStream> result = new ArrayList<>();

    if (Files.exists(base)) {
      for (final String name : names) {
        final File file = new File(base.toFile(), name);
        if (file.exists() && file.canRead()) {
          try {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            ByteStreams.copy(new FileInputStream(file), os);
            final ByteArrayStream stream = new ContentStreamBase.ByteArrayStream(os.toByteArray(), name);
            result.add(stream);
          } catch (final IOException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }

    return result;
  }

  public static void initCore() throws Exception {
    final String home = SolrJettyTestBase.legacyExampleCollection1SolrHome();
    final String config = home + "/" + DEFAULT_CORE_NAME + "/conf/solrconfig.xml";
    final String schema = home + "/" + DEFAULT_CORE_NAME + "/conf/schema.xml";
    initCore(config, schema, home);
  }
}
