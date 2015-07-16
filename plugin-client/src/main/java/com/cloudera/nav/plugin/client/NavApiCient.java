/*
 * Copyright (c) 2015 Cloudera, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.nav.plugin.client;

import com.cloudera.nav.plugin.model.Source;
import com.cloudera.nav.plugin.model.SourceType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * An API client to communicate with Navigator to register and validate
 * metadata models
 */
public class NavApiCient {

  private static final Logger LOG = LoggerFactory.getLogger(NavApiCient.class);
  private static final String SOURCE_QUERY = "type:SOURCE";

  private final PluginConfigurations config;
  private final Cache<String, Source> sourceCacheByUrl;
  private final Cache<SourceType, Collection<Source>> sourceCacheByType;

  public NavApiCient(PluginConfigurations config) {
    this.config = config;
    sourceCacheByUrl = CacheBuilder.newBuilder().build();
    sourceCacheByType = CacheBuilder.newBuilder().build();
  }

  /**
   * Registers a given set of metadata models
   * @param models
   */
  public void registerModels(Collection<Object> models) {
    throw new UnsupportedOperationException("not yet implemented");
  }

  /**
   * Call the Navigator API and retrieve all available sources
   *
   * @return a collection of available sources
   */
  public Collection<Source> getAllSources() {
    RestTemplate restTemplate = new RestTemplate();
    String url = getSourceUrl();
    HttpHeaders headers = getAuthHeaders();
    HttpEntity<String> request = new HttpEntity<String>(headers);
    ResponseEntity<SourceAttrs[]> response = restTemplate.exchange(url,
        HttpMethod.GET, request, SourceAttrs[].class);
    Collection<Source> sources = Lists.newArrayList();
    for (SourceAttrs info : response.getBody()) {
      sources.add(info.createSource());
    }
    return sources;
  }

  /**
   * Constructs relation API call from query, and cursorMark.Returns a batch of
   * results that satisfy the query, starting from the cursorMark.
   * Called in next() of IncrementalExtractIterator()
   *
   * @param queryCriteria Solr query string, cursormark and limit
   * @return ResultsBatch set of results that satisfy query and next cursor
   */
  public ResultsBatch<Map<String, Object>> getRelationBatch(QueryCriteria queryCriteria){
    String fullUrlPost = getUrl("relations");
    return queryNav(fullUrlPost, queryCriteria, RelationResultsBatch.class);
  }

  /**
   * {@link #getRelationBatch(QueryCriteria) getRelationBatch} with entities
   */
  public ResultsBatch<Map<String, Object>> getEntityBatch(QueryCriteria queryCriteria){
    String fullUrlPost = getUrl("entities");
    return queryNav(fullUrlPost, queryCriteria, EntityResultsBatch.class);
  }

  /**
   * Constructs a POST Request from the given URL and body and returns the
   * response body contains a batch of results.
   *
   * @param url URl being posted to
   * @param queryCriteria query criteria for metadata being retrieved to satisfy
   *@param resultClass type of ResultsBatch to be returned
   * @return ResultsBatch of entities or relations that specify the
   * query parameters in the URL and request body
   */
  @VisibleForTesting
  public ResultsBatch<Map<String, Object>> queryNav(String url,
         QueryCriteria queryCriteria,
         Class<? extends ResultsBatch<Map<String, Object>>> resultClass){
    RestTemplate restTemplate = new RestTemplate();
    HttpHeaders headers = getAuthHeaders();
    HttpEntity<QueryCriteria> request =
        new HttpEntity<QueryCriteria>(queryCriteria, headers);
    return restTemplate.exchange(url, HttpMethod.POST, request,
        resultClass).getBody();
  }

  /**
   * Get the Source corresponding to the Hadoop service Url from Navigator.nvmd
   * A NoSuchElementException is thrown if the url does not correspond to
   * any known Source
   *
   * @param serviceUrl
   * @return
   */
  public Source getSourceForUrl(String serviceUrl) {
    Source source = sourceCacheByUrl.getIfPresent(serviceUrl);
    if (source == null) {
      loadAllSources();
    }
    source = sourceCacheByUrl.getIfPresent(serviceUrl);
    Preconditions.checkArgument(source != null,
        "Could not find Source at " + serviceUrl);
    return source;
  }

  /**
   * Return the only Source of the given type, throw exception if more than
   * one is found.
   *
   * @param sourceType
   * @return
   */
  public Source getOnlySource(SourceType sourceType) {
    Collection<Source> sources = getSourcesForType(sourceType);
    Preconditions.checkNotNull(sources, "Could not find sources for " +
        "source type " + sourceType.name());
    return Iterables.getOnlyElement(sources);
  }

  public Collection<Source> getSourcesForType(SourceType sourceType) {
    Collection<Source> sources = sourceCacheByType.getIfPresent(sourceType);
    if (sources == null) {
      loadAllSources();
      sources = sourceCacheByType.getIfPresent(sourceType);
    }
    return sources;
  }

  /**
   * Clear the cache of Sources that have been previously loaded.
   */
  public void resetSources() {
    sourceCacheByUrl.invalidateAll();
    sourceCacheByType.invalidateAll();
  }

  /**
   * Form headers for sending API calls to the Navigator server
   *
   * @return HttpHeaders headers for authorizing the plugin
   */
  private HttpHeaders getAuthHeaders() {
    // basic authentication with base64 encoding
    String plainCreds = String.format("%s:%s", config.getUsername(),
        config.getPassword());
    byte[] plainCredsBytes = plainCreds.getBytes();
    byte[] base64CredsBytes = Base64.encodeBase64(plainCredsBytes);
    String base64Creds = new String(base64CredsBytes);
    HttpHeaders headers = new HttpHeaders();
    headers.add("Authorization", "Basic " + base64Creds);
    return headers;
  }

  /**
   * Get url for querying all sources
   *
   * @return url for querying all sources
   */
  private String getSourceUrl() {
    // form the url string to request all entities with type equal to SOURCE
    String baseNavigatorUrl = config.getNavigatorUrl();
    String entitiesUrl = ClientUtils.joinUrlPath(baseNavigatorUrl, "entities");
    return String.format("%s?query=%s", entitiesUrl, SOURCE_QUERY);
  }

  /**
   * Get URL for incremental extraction
   *
   * @param type "entities", "relations"
   * @return url for querying entities and relations
   */
  private String getUrl(String type) {
    String baseNavigatorUrl = config.getNavigatorUrl();
    String typeUrl = ClientUtils.joinUrlPath(baseNavigatorUrl, type);
    return typeUrl+"/paging";
  }

  private void loadAllSources() {
    Collection<Source> allSources = getAllSources();
    for (Source source : allSources) {
      if (source.getSourceUrl() == null) {
        LOG.warn(String.format("Source %s did not have a source url",
            source.getName() != null ? source.getName() :
                source.getIdentity()));
        continue;
      }
      sourceCacheByUrl.put(source.getSourceUrl(), source);
      try {
        Collection<Source> forType = sourceCacheByType.get(
            source.getSourceType(),
            new Callable<Collection<Source>>() {
              @Override
              public Collection<Source> call() throws Exception {
                return Sets.newHashSet();
              }
            });
        forType.add(source);
      } catch (ExecutionException e) {
        throw Throwables.propagate(e);
      }
    }
  }
}
