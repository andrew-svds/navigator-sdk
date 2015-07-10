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

package com.cloudera.nav.plugin.examples.schema;

import com.cloudera.nav.plugin.model.DatasetIdGenerator;
import com.cloudera.nav.plugin.model.SourceType;
import com.cloudera.nav.plugin.model.annotations.MClass;
import com.cloudera.nav.plugin.model.annotations.MRelation;
import com.cloudera.nav.plugin.model.entities.Dataset;
import com.cloudera.nav.plugin.model.entities.Entity;
import com.cloudera.nav.plugin.model.entities.EntityType;
import com.cloudera.nav.plugin.model.entities.HdfsEntity;
import com.cloudera.nav.plugin.model.relations.RelationRole;
import com.google.common.base.Preconditions;

/**
 * Represents a custom logical dataset with a schema. The fields of the schema
 * are represented as separate {@link com.cloudera.nav.plugin.examples.schema.FireCircleField}
 * entities.
 */
@MClass
public class FireCircleDataset extends Dataset {

  @MRelation(role = RelationRole.PHYSICAL)
  private Entity dataContainer;

  public FireCircleDataset() {
    super();
    setSourceType(SourceType.PLUGIN);
    setNamespace("FireCircle");
  }

  @Override
  public String generateId() {
    return DatasetIdGenerator.datasetId(getDataContainer().getIdentity(),
        getNamespace(), getName());
  }

  /**
   * @return the HDFS directory that contains the data
   */
  public Entity getDataContainer() {
    return dataContainer;
  }

  public void setDataContainer(HdfsEntity hdfsDir) {
    Preconditions.checkArgument(hdfsDir.getEntityType() ==
        EntityType.DIRECTORY);
    this.dataContainer = hdfsDir;
  }
}