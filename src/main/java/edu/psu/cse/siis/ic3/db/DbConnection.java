/*
 * Copyright (C) 2015 The Pennsylvania State University and the University of Wisconsin
 * Systems and Internet Infrastructure Security Laboratory
 *
 * Author: Damien Octeau
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
package edu.psu.cse.siis.ic3.db;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.psu.cse.siis.coal.values.BasePropagationValue;
import edu.psu.cse.siis.coal.values.BottomPropagationValue;
import edu.psu.cse.siis.coal.values.NullPathValue;
import edu.psu.cse.siis.coal.values.PathValue;
import edu.psu.cse.siis.coal.values.PropagationValue;
import edu.psu.cse.siis.coal.values.TopPropagationValue;
import soot.Unit;

public class DbConnection extends SQLConnection {

  public static Map<PathValue, Integer> insertIntentAtExitPoint(String className, String method,
      int instruction, BasePropagationValue intentValue, String exit_kind,
      Set<String> intentPermissions, Integer missingIntents, Set<String> exitPointComponents,
      Map<String, Integer> componentToIdMap, Unit unit) throws SQLException {
    PropagationValue propagationValue = null;
    if (intentValue == null || intentValue instanceof TopPropagationValue
        || intentValue instanceof BottomPropagationValue) {
      missingIntents = 0;
    } else if (intentValue instanceof PropagationValue) {
      propagationValue = (PropagationValue) intentValue;
      if (propagationValue.getPathValues() == null
          || propagationValue.getPathValues().size() == 0) {
        missingIntents = 0;
      }
    } else {
      throw new RuntimeException("Unknown PropagationValue type: " + intentValue.getClass());
    }

    int exitPointId =
        insertExitPoint(className, method, instruction, exit_kind, missingIntents, unit);
    Set<Pair<Integer, Integer>> exitPointComponentPairs = new HashSet<>();

    if (exitPointComponents != null) {
      for (String exitPointComponent : exitPointComponents) {
        exitPointComponentPairs
            .add(new Pair<Integer, Integer>(exitPointId, componentToIdMap.get(exitPointComponent)));
      }
      try {
        exitPointComponentTable.batchInsert(exitPointComponentPairs);
      } catch (Exception e) {

      }
    }
    Map<PathValue, Integer> pairs = new HashMap<PathValue, Integer>();

    // if (intentValue.getIgnored()) {
    // missedIntentTable.insert(exitPointId, Constants.NOT_FOUND);
    // } else {

    if (missingIntents == null) {
      Set<PathValue> singleIntentValues = propagationValue.getPathValues();
      if (singleIntentValues != null) {
        for (PathValue singleIntentValue : singleIntentValues) {
          if (exit_kind.equals(Constants.ComponentShortType.PROVIDER)) {
            insertUriAndValue(exitPointId, singleIntentValue);
          } else {
            int intentId = insertIntentAndValue(exitPointId, singleIntentValue);
            if (intentId != Constants.NOT_FOUND) {
              pairs.put(singleIntentValue, intentId);
            }
          }
        }
      }
    }
    // }

    if (intentPermissions != null) {
      for (String intentPermission : intentPermissions) {
        insertIntentPermission(exitPointId, intentPermission);
      }
    }

    return pairs;
  }

  public static void insertComponentExtras(Set<String> entryPoints,
      Map<String, Integer> componentToIdMap, Set<String> extras) throws SQLException {
    if (entryPoints == null) {
      return;
    }
    for (String entryPoint : entryPoints) {
      for (String extra : extras) {

        Integer compID = componentToIdMap.get(entryPoint);
        if (compID != null) {
          componentExtraTable.insert(compID, extra);
        }
      }
    }
  }

  protected static int insertIntentAndValue(int exitPointId, PathValue singleIntentValue)
      throws SQLException {
    // System.out.println(singleIntentValue);

    List<Integer> actionIds = new ArrayList<Integer>();
    List<Integer> categoryIds = new ArrayList<Integer>();

    insertStrings(actionStringTable,
        Collections.singleton(singleIntentValue.getScalarStringFieldValue("action")), actionIds);
    insertStrings(categoryStringTable, singleIntentValue.getSetStringFieldValue("categories"),
        categoryIds);

    // if (find) {
    // int intentId =
    // intentTable.find(exitPointId, actionIds, categoryIds,
    // singleIntentValue.getSingleStringFieldValue("type"),
    // singleIntentValue.getStringFieldValue("extras"), false);
    // if (intentId != Constants.NOT_FOUND) {
    // return intentId;
    // }
    // }

    int intentId = intentTable.forceInsert(exitPointId,
        singleIntentValue.getScalarStringFieldValue("clazz") == null, false);

    // for (int actionId : actionIds) {
    // intentActionTable.forceInsert(intentId, actionId);
    // }
    intentActionTable.batchForceInsert(intentId, actionIds);
    // for (int categoryId : categoryIds) {
    // intentCategoryTable.forceInsert(intentId, categoryId);
    // }
    intentCategoryTable.batchForceInsert(intentId, categoryIds);
    String type = singleIntentValue.getScalarStringFieldValue("dataType");
    if (type != null) {
      String[] typeParts = null;
      if (type.equals(Constants.ANY_STRING)) {
        typeParts = new String[] { Constants.ANY_STRING, Constants.ANY_STRING };
      } else {
        typeParts = type.split("/");
        if (typeParts.length != 2) {
          typeParts = null;
        }
      }
      if (typeParts != null) {
        intentMimeTypeTable.forceInsert(intentId, typeParts[0], typeParts[1]);
      }
    }
    Set<String> extras = singleIntentValue.getSetStringFieldValue("extras");
    if (extras != null) {
      for (String extra : extras) {
        intentExtraTable.forceInsert(intentId, extra);
      }
    }

    int dataId = insertData(singleIntentValue);
    if (dataId != Constants.NOT_FOUND) {
      intentDataTable.forceInsert(intentId, dataId);
    }

    String clazz = singleIntentValue.getScalarStringFieldValue("clazz");
    if (clazz != null) {
      intentClassTable.insert(intentId, clazz);
    }

    String pkg = singleIntentValue.getScalarStringFieldValue("package");
    if (pkg != null) {
      intentPackageTable.insert(intentId, pkg);
    }

    return intentId;
  }

  protected static void insertUriAndValue(int exitPointId, PathValue singleIntentValue)
      throws SQLException {
    if (singleIntentValue instanceof NullPathValue) {
      return;
    }

    int dataId = insertData(singleIntentValue);

    uriTable.forceInsert(exitPointId, dataId != Constants.NOT_FOUND ? dataId : null);
  }

  protected static int insertData(PathValue singleIntentValue) throws SQLException {
    if (singleIntentValue.containsNonNullFieldValue("scheme")
        || singleIntentValue.containsNonNullFieldValue("ssp")
        || singleIntentValue.containsNonNullFieldValue("uri")
        || singleIntentValue.containsNonNullFieldValue("path")
        || singleIntentValue.containsNonNullFieldValue("query")
        || singleIntentValue.containsNonNullFieldValue("authority")) {
      return uriDataTable.forceInsert(singleIntentValue.getScalarStringFieldValue("scheme"),
          singleIntentValue.getScalarStringFieldValue("ssp"),
          singleIntentValue.getScalarStringFieldValue("uri"),
          singleIntentValue.getScalarStringFieldValue("path"),
          singleIntentValue.getScalarStringFieldValue("query"),
          singleIntentValue.getScalarStringFieldValue("authority"));
    } else {
      return Constants.NOT_FOUND;
    }
  }

}
