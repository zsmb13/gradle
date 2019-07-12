/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance.results

import groovy.transform.CompileStatic
import org.gradle.performance.measure.DataSeries
import org.gradle.performance.measure.Duration
import org.gradle.performance.measure.MeasuredOperation

@CompileStatic
public class MeasuredOperationList extends LinkedList<MeasuredOperation> {
    String name

    DataSeries<Duration> getTotalTime() { new DataSeries<Duration>(collect { it.totalTime }) }

    String getSpeedStats() {
        """
  ${name}
   median: ${totalTime.median.format()}, 
   min: ${totalTime.min.format()}, 
   max: ${totalTime.max.format()}, 
   se: ${totalTime.standardError.format()}
   >run: ${totalTime.collect { it.format() }}
   >gct  ${this*.gcTime}
   >gcc: ${this*.gcTime}
"""
    }
}
