/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.nodelabels.CommonNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.utils.Lock;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;

class CSQueueUtils {
  
  private static final Log LOG = LogFactory.getLog(CSQueueUtils.class);

  final static float EPSILON = 0.0001f;
  
  /*
   * Used only by tests
   */
  public static void checkMaxCapacity(String queueName, 
      float capacity, float maximumCapacity) {
    if (maximumCapacity < 0.0f || maximumCapacity > 1.0f) {
      throw new IllegalArgumentException(
          "Illegal value  of maximumCapacity " + maximumCapacity + 
          " used in call to setMaxCapacity for queue " + queueName);
    }
    }

  /*
   * Used only by tests
   */
  public static void checkAbsoluteCapacity(String queueName,
      float absCapacity, float absMaxCapacity) {
    if (absMaxCapacity < (absCapacity - EPSILON)) {
      throw new IllegalArgumentException("Illegal call to setMaxCapacity. "
          + "Queue '" + queueName + "' has " + "an absolute capacity (" + absCapacity
          + ") greater than " + "its absolute maximumCapacity (" + absMaxCapacity
          + ")");
  }
  }
  
  /**
   * Check sanity of capacities:
   * - capacity <= maxCapacity
   * - absCapacity <= absMaximumCapacity
   */
  private static void capacitiesSanityCheck(String queueName,
      QueueCapacities queueCapacities) {
    for (String label : queueCapacities.getExistingNodeLabels()) {
      float capacity = queueCapacities.getCapacity(label);
      float maximumCapacity = queueCapacities.getMaximumCapacity(label);
      if (capacity > maximumCapacity) {
        throw new IllegalArgumentException("Illegal queue capacity setting, "
            + "(capacity=" + capacity + ") > (maximum-capacity="
            + maximumCapacity + "). When label=[" + label + "]");
      }
     
      // Actually, this may not needed since we have verified capacity <=
      // maximumCapacity. And the way we compute absolute capacity (abs(x) =
      // cap(x) * cap(x.parent) * ...) is a monotone increasing function. But
      // just keep it here to make sure our compute abs capacity method works
      // correctly. 
      float absCapacity = queueCapacities.getAbsoluteCapacity(label);
      float absMaxCapacity = queueCapacities.getAbsoluteMaximumCapacity(label);
      if (absCapacity > absMaxCapacity) {
        throw new IllegalArgumentException("Illegal queue capacity setting, "
            + "(abs-capacity=" + absCapacity + ") > (abs-maximum-capacity="
            + absMaxCapacity + "). When label=[" + label + "]");
      }
    }
  }

  public static float computeAbsoluteMaximumCapacity(
      float maximumCapacity, CSQueue parent) {
    float parentAbsMaxCapacity = 
        (parent == null) ? 1.0f : parent.getAbsoluteMaximumCapacity();
    return (parentAbsMaxCapacity * maximumCapacity);
  }
  
  /**
   * This method intends to be used by ReservationQueue, ReservationQueue will
   * not appear in configuration file, so we shouldn't do load capacities
   * settings in configuration for reservation queue.
   */
  public static void updateAndCheckCapacitiesByLabel(String queuePath,
      QueueCapacities queueCapacities, QueueCapacities parentQueueCapacities) {
    updateAbsoluteCapacitiesByNodeLabels(queueCapacities, parentQueueCapacities);

    capacitiesSanityCheck(queuePath, queueCapacities);
  }

  /**
   * Do following steps for capacities
   * - Load capacities from configuration
   * - Update absolute capacities for new capacities
   * - Check if capacities/absolute-capacities legal
   */
  public static void loadUpdateAndCheckCapacities(String queuePath,
      Set<String> accessibleLabels, CapacitySchedulerConfiguration csConf,
      QueueCapacities queueCapacities, QueueCapacities parentQueueCapacities,
      RMNodeLabelsManager nlm) {
    loadCapacitiesByLabelsFromConf(queuePath, accessibleLabels, nlm,
        queueCapacities, csConf);

    updateAbsoluteCapacitiesByNodeLabels(queueCapacities, parentQueueCapacities);

    capacitiesSanityCheck(queuePath, queueCapacities);
  }
  
  // Considered NO_LABEL, ANY and null cases
  private static Set<String> normalizeAccessibleNodeLabels(Set<String> labels,
      RMNodeLabelsManager mgr) {
    Set<String> accessibleLabels = new HashSet<String>();
    if (labels != null) {
      accessibleLabels.addAll(labels);
    }
    if (accessibleLabels.contains(CommonNodeLabelsManager.ANY)) {
      accessibleLabels.addAll(mgr.getClusterNodeLabels());
    }
    accessibleLabels.add(CommonNodeLabelsManager.NO_LABEL);
    
    return accessibleLabels;
  }
  
  private static void loadCapacitiesByLabelsFromConf(String queuePath,
      Set<String> labels, RMNodeLabelsManager mgr,
      QueueCapacities queueCapacities, CapacitySchedulerConfiguration csConf) {
    queueCapacities.clearConfigurableFields();
    labels = normalizeAccessibleNodeLabels(labels, mgr);

    for (String label : labels) {
      if (label.equals(CommonNodeLabelsManager.NO_LABEL)) {
        queueCapacities.setCapacity(CommonNodeLabelsManager.NO_LABEL,
            csConf.getNonLabeledQueueCapacity(queuePath) / 100);
        queueCapacities.setMaximumCapacity(CommonNodeLabelsManager.NO_LABEL,
            csConf.getNonLabeledQueueMaximumCapacity(queuePath) / 100);
      } else {
        queueCapacities.setCapacity(label,
            csConf.getLabeledQueueCapacity(queuePath, label) / 100);
        queueCapacities.setMaximumCapacity(label,
            csConf.getLabeledQueueMaximumCapacity(queuePath, label) / 100);
      }
    }
  }
  
  // Set absolute capacities for {capacity, maximum-capacity}
  private static void updateAbsoluteCapacitiesByNodeLabels(
      QueueCapacities queueCapacities, QueueCapacities parentQueueCapacities) {
    for (String label : queueCapacities.getExistingNodeLabels()) {
      float capacity = queueCapacities.getCapacity(label);
      if (capacity > 0f) {
        queueCapacities.setAbsoluteCapacity(
            label,
            capacity
                * (parentQueueCapacities == null ? 1 : parentQueueCapacities
                    .getAbsoluteCapacity(label)));
      }

      float maxCapacity = queueCapacities.getMaximumCapacity(label);
      if (maxCapacity > 0f) {
        queueCapacities.setAbsoluteMaximumCapacity(
            label,
            maxCapacity
                * (parentQueueCapacities == null ? 1 : parentQueueCapacities
                    .getAbsoluteMaximumCapacity(label)));
      }
    }
  }
  
  @Lock(CSQueue.class)
  public static void updateQueueStatistics(
      final ResourceCalculator calculator,
      final CSQueue childQueue, final CSQueue parentQueue, 
      final Resource clusterResource, final Resource minimumAllocation) {
    Resource queueLimit = Resources.none();
    Resource usedResources = childQueue.getUsedResources();
    
    float absoluteUsedCapacity = 0.0f;
    float usedCapacity = 0.0f;

    if (Resources.greaterThan(
        calculator, clusterResource, clusterResource, Resources.none())) {
      queueLimit = 
          Resources.multiply(clusterResource, childQueue.getAbsoluteCapacity());
      absoluteUsedCapacity = 
          Resources.divide(calculator, clusterResource, 
              usedResources, clusterResource);
      usedCapacity = 
          Resources.equals(queueLimit, Resources.none()) ? 0 :
          Resources.divide(calculator, clusterResource, 
              usedResources, queueLimit);
    }

    childQueue.setUsedCapacity(usedCapacity);
    childQueue.setAbsoluteUsedCapacity(absoluteUsedCapacity);
    
    Resource available = Resources.subtract(queueLimit, usedResources);
    childQueue.getMetrics().setAvailableResourcesToQueue(
        Resources.max(
            calculator, 
            clusterResource, 
            available, 
            Resources.none()
            )
        );
   }

   public static float getAbsoluteMaxAvailCapacity(
      ResourceCalculator resourceCalculator, Resource clusterResource, CSQueue queue) {
      CSQueue parent = queue.getParent();
      if (parent == null) {
        return queue.getAbsoluteMaximumCapacity();
      }

      //Get my parent's max avail, needed to determine my own
      float parentMaxAvail = getAbsoluteMaxAvailCapacity(
        resourceCalculator, clusterResource, parent);
      //...and as a resource
      Resource parentResource = Resources.multiply(clusterResource, parentMaxAvail);

      //check for no resources parent before dividing, if so, max avail is none
      if (Resources.isInvalidDivisor(resourceCalculator, parentResource)) {
        return 0.0f;
      }
      //sibling used is parent used - my used...
      float siblingUsedCapacity = Resources.ratio(
                 resourceCalculator,
                 Resources.subtract(parent.getUsedResources(), queue.getUsedResources()),
                 parentResource);
      //my max avail is the lesser of my max capacity and what is unused from my parent
      //by my siblings (if they are beyond their base capacity)
      float maxAvail = Math.min(
        queue.getMaximumCapacity(),
        1.0f - siblingUsedCapacity);
      //and, mutiply by parent to get absolute (cluster relative) value
      float absoluteMaxAvail = maxAvail * parentMaxAvail;

      if (LOG.isDebugEnabled()) {
        LOG.debug("qpath " + queue.getQueuePath());
        LOG.debug("parentMaxAvail " + parentMaxAvail);
        LOG.debug("siblingUsedCapacity " + siblingUsedCapacity);
        LOG.debug("getAbsoluteMaximumCapacity " + queue.getAbsoluteMaximumCapacity());
        LOG.debug("maxAvail " + maxAvail);
        LOG.debug("absoluteMaxAvail " + absoluteMaxAvail);
      }

      if (absoluteMaxAvail < 0.0f) {
        absoluteMaxAvail = 0.0f;
      } else if (absoluteMaxAvail > 1.0f) {
        absoluteMaxAvail = 1.0f;
      }

      return absoluteMaxAvail;
   }
}
