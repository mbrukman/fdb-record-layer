/*
 * AnyChildWithRestMatcher.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2019 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.query.plan.temp.matchers;

import com.apple.foundationdb.API;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.query.plan.temp.ExpressionRef;
import com.apple.foundationdb.record.query.plan.temp.PlannerExpression;
import com.google.common.collect.Lists;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * An expression matcher that tries to match any child to the given {@link ExpressionMatcher} while providing a
 * {@link ReferenceMatcher} binding to all other children. This can be quite useful when matching to a planner
 * expression that might have an unbounded number of children when a planner rule wants to inspect exactly one of those
 * children deeply. This matcher might produce several possible bindings because the {@code ExpressionMatcher} for the
 * distinguished child might match several children.
 * @param <T> the type of the matcher for the selected child
 */
@API(API.Status.EXPERIMENTAL)
public class AnyChildWithRestMatcher<T extends PlannerExpression> implements ExpressionChildrenMatcher {
    @Nonnull
    private ExpressionMatcher<T> selectedChildMatcher;
    @Nonnull
    private AllChildrenMatcher otherChildrenMatcher;

    private AnyChildWithRestMatcher(@Nonnull ExpressionMatcher<T> selectedChildMatcher,
                                    @Nonnull ReferenceMatcher<? super T> otherChildrenMatcher) {
        this.selectedChildMatcher = selectedChildMatcher;
        this.otherChildrenMatcher = new AllChildrenMatcher(otherChildrenMatcher);
    }

    @Nonnull
    @Override
    public Stream<PlannerBindings> matches(@Nonnull Iterator<? extends ExpressionRef<? extends PlannerExpression>> childIterator) {
        List<? extends ExpressionRef<? extends PlannerExpression>> children = Lists.newArrayList(childIterator);

        Stream.Builder<Stream<PlannerBindings>> streams = Stream.builder();
        for (int i = 0; i < children.size(); i++) {
            ExpressionRef<? extends PlannerExpression> child = children.get(i);
            List<ExpressionRef<? extends PlannerExpression>> otherChildren = new ArrayList<>(children.size() - 1);
            otherChildren.addAll(children.subList(0, i));
            otherChildren.addAll(children.subList(i + 1, children.size()));

            Stream<PlannerBindings> childBindings = child.bindTo(selectedChildMatcher);
            // The otherChildrenMatcher is an AllChildrenMatcher wrapping a ReferenceMatcher, so it is guaranteed to
            // produce a single set of PlannerBindings.
            Optional<PlannerBindings> otherBindings = otherChildrenMatcher.matches(otherChildren.iterator()).findFirst();
            if (!otherBindings.isPresent()) {
                throw new RecordCoreException("invariant violated: couldn't match reference matcher to one of the other children");
            }
            streams.add(childBindings.map(selectedBindings -> selectedBindings.mergedWith(otherBindings.get())));
        }
        return streams.build().flatMap(Function.identity());
    }

    @Nonnull
    public static <T extends PlannerExpression> AnyChildWithRestMatcher<T> anyMatchingWithRest(
            @Nonnull ExpressionMatcher<T> selectedChildMatcher,
            @Nonnull ReferenceMatcher<? super T> otherChildrenMatcher) {
        return new AnyChildWithRestMatcher<>(selectedChildMatcher, otherChildrenMatcher);
    }
}
