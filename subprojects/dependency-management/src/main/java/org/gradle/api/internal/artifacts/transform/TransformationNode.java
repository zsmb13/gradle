/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.execution.plan.Node;
import org.gradle.execution.plan.SelfExecutingNode;
import org.gradle.execution.plan.TaskDependencyResolver;
import org.gradle.internal.Try;
import org.gradle.internal.operations.BuildOperationCategory;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.internal.scan.UsedByScanPlugin;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class TransformationNode extends Node implements SelfExecutingNode {
    private static final AtomicInteger ORDER_COUNTER = new AtomicInteger();

    private final int order = ORDER_COUNTER.incrementAndGet();
    protected final TransformationStep transformationStep;
    protected final ExecutionGraphDependenciesResolver dependenciesResolver;
    protected final BuildOperationExecutor buildOperationExecutor;
    protected final ArtifactTransformListener transformListener;
    protected Try<TransformationSubject> transformedSubject;

    public static ChainedTransformationNode chained(TransformationStep current, TransformationNode previous, ExecutionGraphDependenciesResolver executionGraphDependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener, TransformationNodeRegistry transformationNodeRegistry) {
        return new ChainedTransformationNode(current, previous, executionGraphDependenciesResolver, buildOperationExecutor, transformListener, transformationNodeRegistry);
    }

    public static InitialTransformationNode initial(TransformationStep initial, ResolvableArtifact artifact, ExecutionGraphDependenciesResolver executionGraphDependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener, TransformationNodeRegistry transformationNodeRegistry) {
        return new InitialTransformationNode(initial, artifact, executionGraphDependenciesResolver, buildOperationExecutor, transformListener, transformationNodeRegistry);
    }

    protected TransformationNode(TransformationStep transformationStep, ExecutionGraphDependenciesResolver dependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener) {
        this.transformationStep = transformationStep;
        this.dependenciesResolver = dependenciesResolver;
        this.buildOperationExecutor = buildOperationExecutor;
        this.transformListener = transformListener;
    }

    @Nullable
    @Override
    public Project getOwningProject() {
        return transformationStep.getOwningProject();
    }

    @Override
    public boolean isPublicNode() {
        return true;
    }

    @Override
    public boolean requiresMonitoring() {
        return false;
    }

    @Override
    public void resolveMutations() {
        // Assume for now that no other node is going to destroy the transform outputs, or overlap with them
    }

    @Override
    public String toString() {
        return transformationStep.getDisplayName();
    }

    public TransformationStep getTransformationStep() {
        return transformationStep;
    }

    public ExecutionGraphDependenciesResolver getDependenciesResolver() {
        return dependenciesResolver;
    }

    public Try<TransformationSubject> getTransformedSubject() {
        if (transformedSubject == null) {
            throw new IllegalStateException(String.format("Transformation %s has been scheduled and is now required, but did not execute, yet.", transformationStep.getDisplayName()));
        }
        return transformedSubject;
    }

    @Override
    public Set<Node> getFinalizers() {
        return Collections.emptySet();
    }

    @Override
    public void prepareForExecution() {
    }

    @Nullable
    @Override
    public Project getProjectToLock() {
        // Transforms do not require project state
        return null;
    }

    @Override
    public List<ResourceLock> getResourcesToLock() {
        return Collections.emptyList();
    }

    @Override
    public Throwable getNodeFailure() {
        return null;
    }

    @Override
    public void rethrowNodeFailure() {
    }

    @Override
    public int compareTo(Node other) {
        if (getClass() != other.getClass()) {
            return getClass().getName().compareTo(other.getClass().getName());
        }
        TransformationNode otherTransformation = (TransformationNode) other;
        return order - otherTransformation.order;
    }

    protected void processDependencies(Action<Node> processHardSuccessor, Set<Node> dependencies) {
        for (Node dependency : dependencies) {
            addDependencySuccessor(dependency);
            processHardSuccessor.execute(dependency);
        }
    }

    @Override
    public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
        processDependencies(processHardSuccessor, dependencyResolver.resolveDependenciesFor(null, transformationStep.getDependencies()));
        processDependencies(processHardSuccessor, dependencyResolver.resolveDependenciesFor(null, dependenciesResolver.computeDependencyNodes(transformationStep)));
    }

    public static class InitialTransformationNode extends TransformationNode {
        private final ResolvableArtifact artifact;
        private final TransformationNodeRegistry transformationNodeRegistry;

        public InitialTransformationNode(TransformationStep transformationStep, ResolvableArtifact artifact, ExecutionGraphDependenciesResolver dependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener, TransformationNodeRegistry transformationNodeRegistry) {
            super(transformationStep, dependenciesResolver, buildOperationExecutor, transformListener);
            this.artifact = artifact;
            this.transformationNodeRegistry = transformationNodeRegistry;
        }

        public ResolvableArtifact getInputArtifact() {
            return artifact;
        }

        @Override
        public void execute(NodeExecutionContext context) {
            this.transformedSubject = buildOperationExecutor.call(new ArtifactTransformationStepBuildOperation() {
                @Override
                protected Try<TransformationSubject> transform() {
                    Map<ComponentArtifactIdentifier, TransformationResult> artifactResults = Maps.newConcurrentMap();
                    buildOperationExecutor.runAll(queue -> {
                        TransformingAsyncArtifactListener visitor = new TransformingAsyncArtifactListener(transformationStep, queue, artifactResults, getDependenciesResolver(), transformationNodeRegistry, context, false);
                        visitor.artifactAvailable(artifact);
                    });
                    return artifactResults.get(artifact.getId()).getTransformedSubject();
                }

                @Override
                protected String describeSubject() {
                    return "artifact " + artifact.getId().getDisplayName();
                }
            });
        }

        @Override
        public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
            super.resolveDependencies(dependencyResolver, processHardSuccessor);
            processDependencies(processHardSuccessor, dependencyResolver.resolveDependenciesFor(null, artifact));
        }
    }

    public static class ChainedTransformationNode extends TransformationNode {
        private final TransformationNode previousTransformationNode;
        private final TransformationNodeRegistry transformationNodeRegistry;

        public ChainedTransformationNode(TransformationStep transformationStep, TransformationNode previousTransformationNode, ExecutionGraphDependenciesResolver dependenciesResolver, BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener, TransformationNodeRegistry transformationNodeRegistry) {
            super(transformationStep, dependenciesResolver, buildOperationExecutor, transformListener);
            this.previousTransformationNode = previousTransformationNode;
            this.transformationNodeRegistry = transformationNodeRegistry;
        }

        public TransformationNode getPreviousTransformationNode() {
            return previousTransformationNode;
        }

        @Override
        public void execute(NodeExecutionContext context) {
            this.transformedSubject = buildOperationExecutor.call(new ArtifactTransformationStepBuildOperation() {
                @Override
                protected Try<TransformationSubject> transform() {
                    return previousTransformationNode.getTransformedSubject().flatMap(inputSubject -> {
                        Map<ComponentArtifactIdentifier, TransformationResult> artifactResults = Maps.newConcurrentMap();
                        buildOperationExecutor.runAll(queue -> {
                            TransformingAsyncArtifactListener visitor = new TransformingAsyncArtifactListener(transformationStep, queue, artifactResults, getDependenciesResolver(), transformationNodeRegistry, context, false);
                            for (ResolvableArtifact artifact : inputSubject.getArtifacts()) {
                                visitor.artifactAvailable(artifact);
                            }
                        });
                        ImmutableList.Builder<File> builder = ImmutableList.builderWithExpectedSize(artifactResults.size());
                        for (ResolvableArtifact artifact : inputSubject.getArtifacts()) {
                            TransformationResult result = artifactResults.get(artifact.getId());
                            Try<TransformationSubject> transformedSubject = result.getTransformedSubject();
                            if (!transformedSubject.isSuccessful()) {
                                // TODO - should collect all of the failures
                                return transformedSubject;
                            }
                            builder.addAll(transformedSubject.get().getFiles());
                        }
                        return Try.successful(inputSubject.createSubjectFromResult(builder.build()));
                    });
                }

                @Override
                protected String describeSubject() {
                    return previousTransformationNode.getTransformedSubject()
                        .map(subject -> subject.getDisplayName())
                        .getOrMapFailure(failure -> failure.getMessage());
                }
            });
        }

        @Override
        public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
            super.resolveDependencies(dependencyResolver, processHardSuccessor);
            addDependencySuccessor(previousTransformationNode);
            processHardSuccessor.execute(previousTransformationNode);
        }

    }

    private abstract class ArtifactTransformationStepBuildOperation implements CallableBuildOperation<Try<TransformationSubject>> {

        @UsedByScanPlugin("The string is used for filtering out artifact transform logs in Gradle Enterprise")
        private static final String TRANSFORMING_PROGRESS_PREFIX = "Transforming ";

        @Override
        public final BuildOperationDescriptor.Builder description() {
            String transformerName = transformationStep.getDisplayName();
            String subjectName = describeSubject();
            String basicName = subjectName + " with " + transformerName;
            return BuildOperationDescriptor.displayName("Transform " + basicName)
                .progressDisplayName(TRANSFORMING_PROGRESS_PREFIX + basicName)
                .operationType(BuildOperationCategory.TRANSFORM)
                .details(new ExecuteScheduledTransformationStepBuildOperationDetails(TransformationNode.this, transformerName, subjectName));
        }

        protected abstract String describeSubject();

        @Override
        public Try<TransformationSubject> call(BuildOperationContext context) {
            Try<TransformationSubject> transformedSubject = transform();
            context.setResult(ExecuteScheduledTransformationStepBuildOperationType.RESULT);
            transformedSubject.getFailure().ifPresent(failure -> context.failed(failure));
            return transformedSubject;
        }

        protected abstract Try<TransformationSubject> transform();
    }

}
