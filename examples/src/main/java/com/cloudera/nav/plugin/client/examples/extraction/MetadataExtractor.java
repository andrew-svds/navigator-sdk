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
package com.cloudera.nav.plugin.client.examples.extraction;

import com.cloudera.com.fasterxml.jackson.core.type.TypeReference;
import com.cloudera.com.fasterxml.jackson.databind.ObjectMapper;
import com.cloudera.nav.plugin.client.NavApiCient;
import com.cloudera.nav.plugin.model.Source;
import com.cloudera.nav.plugin.model.SourceType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;


import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

/**
 * Sample for showing incremental extraction using the plugin client library. The
 * extractMetadata() method has signatures for calling with and without a marker,
 * and with specifying a filter query string for entities and relations to be
 * returned.
 *
 * Marker is determined from a file path in system arguments. If none is specified,
 * all metadata will be extracted and the current marker written to testMarker.txt
 */
public class MetadataExtractor {

  private NavApiCient client;
  private static final String DEFAULT_QUERY= "identity:*";
  private static final Integer DEFAULT_LIMIT = 100;
  private final Integer limit;

  public MetadataExtractor(NavApiCient client, Integer limit) {
    this.client = client;
    this.limit = (limit == null) ? DEFAULT_LIMIT : limit;
  }

  /**
   * Returns all of the entities and relations in the database,
   * plus a marker to denote when this search took place
   *
   * @return MetadataResultSet wrapper with iterables for all entities and relations
   * and string of next marker
   */
  public MetadataResultSet extractMetadata(){
    return extractMetadata(null, null, DEFAULT_QUERY, DEFAULT_QUERY);
  }

  /**
   * Perform incremental extraction for the entities and relations in the
   * database that have been updated or added since the extraction indicated by the marker.
   *
   * @param markerRep String from previous extractMetadata call
   * @return MetadataResultSet wrapper with iterables for updated entities and relations
   * and string of the next marker
   */
  public MetadataResultSet extractMetadata(String markerRep){
    return extractMetadata(markerRep, null, DEFAULT_QUERY, DEFAULT_QUERY);
  }

  public MetadataResultSet extractMetadata(String startMarker, String endMarker){
    return extractMetadata(startMarker, endMarker, DEFAULT_QUERY, DEFAULT_QUERY);
  }

  /**
   * Perform an incremental extraction for all entities and relations that
   * satisfy the specified queries and have been added or updated since the extraction
   * indicated by the marker.
   *
   * @param startMarkerRep String from previous extractMetadata call
   * @param entitiesQuery Solr query string for specifying entities
   * @param relationsQuery Solr query string for specifying relations
   * @return MetadataResultSet wrapper with iterables for resulting updated entities
   * and relations and string of the next marker
   */
  public MetadataResultSet extractMetadata(String startMarkerRep,
                                           String endMarkerRep,
                                           String entitiesQuery,
                                           String relationsQuery){
    try {
      TypeReference<Map<String, Integer>> typeRef =
          new TypeReference<Map<String, Integer>>(){};
      Iterable<String> extractorQuery;
      Map<String, Integer> endMarker;
      if(StringUtils.isEmpty(startMarkerRep) && StringUtils.isEmpty(endMarkerRep)){
        extractorQuery = Lists.newArrayList("*");
      } else {
        Map<String, Integer> startMarker;
        if(StringUtils.isEmpty(startMarkerRep)) {
          startMarker = getNavMarker(false);
        } else {
          startMarker = new ObjectMapper().readValue(startMarkerRep, typeRef);
        }
        if(StringUtils.isEmpty(endMarkerRep)) {
          endMarker = getNavMarker(true);
        } else {
          endMarker = new ObjectMapper().readValue(endMarkerRep, typeRef);
        }
        extractorQuery = getExtractorQueryList(startMarker, endMarker);
      }
      String currentMarkerRep = new ObjectMapper().writeValueAsString(getNavMarker(true));
      return aggUpdatedResults(currentMarkerRep, extractorQuery, entitiesQuery, relationsQuery);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Generate marker from each source and its sourceExtractIteration that
   * can be used to form extractorRunIds
   *
   * @return Map of sourceId to its to extractIteration
   */
  private Map<String, Integer> getNavMarker(boolean current){
    Collection<Source> sources = client.getAllSources();
    HashMap<String, Integer> newMarker = Maps. newHashMap();
    for (Source source : sources) {
      //Source types without source IDs or extractorRunIds are unsupported
      List<SourceType> unsupportedTypes = Lists.newArrayList(SourceType.PIG,
          SourceType.IMPALA, SourceType.SPARK, SourceType.SQOOP);
      if (unsupportedTypes.contains(source.getSourceType())) {
        continue;
      }
      String id = source.getIdentity();
      Integer sourceExtractIteration = (current) ? source.getSourceExtractIteration() : 0;
      newMarker.put(id, sourceExtractIteration);
    }
    return newMarker;
  }

  /**
   * Returns an iterable of all possible extractorRunIds in between the extraction
   * states specified by marker m1 and marker m2.
   *
   * @param m1 Marker for past extraction state
   * @param m2 Marker for later(current) extraction state
   * @return Iterable of possible extractorRunIds to be used in queries
   */
  private Iterable<String> getExtractorQueryList(Map<String, Integer> m1,
                                         Map<String, Integer> m2){
    List<String> runIdList= Lists.newArrayList();
    for (String key: m1.keySet()){
      for (int i=m1.get(key); i<(m2.get(key)+1); i++){
        String possible = key + "##" + Integer.toString(i);
        runIdList.add(possible);
      }
    }
    return runIdList;
  }

  /**
   * Constructs an MetadataResultSet object with results of getAllPages
   * for entities and relations, and the marker used to generate these results.
   *
   * @param markerRep String marker from previous extractMetadata call
   * @param extractorRunIds List of possible extractorRunIds
   * @param entitiesQuery Query string for filtering entities to extract
   * @param relationsQuery Query string filtering relations to extract
   * @return MetadataResultSet with resulting entities, relations and marker
   */
  private MetadataResultSet aggUpdatedResults(String markerRep,
                                          Iterable<String> extractorRunIds,
                                          String entitiesQuery,
                                          String relationsQuery){
    MetadataResultSet metadataResultSet;
    IncrementalExtractIterable entities =
        new IncrementalExtractIterable(client, MetadataType.ENTITIES,
                                       entitiesQuery, limit, extractorRunIds);
    IncrementalExtractIterable relations =
        new IncrementalExtractIterable(client, MetadataType.RELATIONS,
                                       relationsQuery, limit, extractorRunIds);
    metadataResultSet = new MetadataResultSet(markerRep, entities, relations);
    return metadataResultSet;
  }

  /**
   *  Writes the marker for the current state of the sources as a string
   *
   * @return String representation of a marker
   */
  @VisibleForTesting
  String getMarker(){
    Map<String, Integer> currentMarker = getNavMarker(true);
    try {
      String rep = new ObjectMapper().writeValueAsString(currentMarker);
      return rep;
    } catch (IOException e){
      throw Throwables.propagate(e);
    }
  }
}