/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.api.tasks.ide.eclipse;


import org.gradle.api.internal.ConventionTask
import org.gradle.api.tasks.AbstractSpockTaskTest
import org.gradle.plugins.eclipse.model.Facet
import org.gradle.plugins.eclipse.model.Wtp
import org.gradle.plugins.eclipse.model.internal.ModelFactory
import org.gradle.util.TemporaryFolder
import org.junit.Rule

/**
 * @author Hans Dockter
 */
public class EclipseWtpTest extends AbstractSpockTaskTest {
    EclipseWtp eclipseWtp

    @Rule
    TemporaryFolder tmpDir = new TemporaryFolder()

    ConventionTask getTask() {
        return eclipseWtp
    }

    def setup() {
        eclipseWtp = createTask(EclipseWtp.class);
    }

    def facet_shouldAdd() {
        when:
        eclipseWtp.facet name: 'facet1', version: '1.0'
        eclipseWtp.facet name: 'facet2', version: '2.0'

        then:
        eclipseWtp.facets = [new Facet('facet1', '1.0'), new Facet('facet2', '2.0')]
    }

    def generateXml() {
        ModelFactory modelFactory = Mock()
        Wtp wtp = Mock()
        eclipseWtp.modelFactory = modelFactory
        eclipseWtp.setOrgEclipseWstCommonComponentOutputFile(tmpDir.file("component"))
        eclipseWtp.setOrgEclipseWstCommonProjectFacetCoreOutputFile(tmpDir.file("facet"))
        modelFactory.createWtp(eclipseWtp) >> wtp

        when:
        eclipseWtp.generateXml()

        then:
        1 * wtp.toXml(eclipseWtp.orgEclipseWstCommonComponentOutputFile, eclipseWtp.orgEclipseWstCommonProjectFacetCoreOutputFile)
    }

    def variables_shouldAdd() {
        when:
        eclipseWtp.variables variable1: 'value1'
        eclipseWtp.variables variable2: 'value2'

        then:
        eclipseWtp.variables == [variable1: 'value1', variable2: 'value2']
    }
}
