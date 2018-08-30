/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.util.function;

/**
 * Represents a predicate of one argument. This is a poor man's backport of the
 * <tt>Predicate</tt> interface found in Java 1.8.
 *
 * @author George Politis
 * @deprecated Use {@link java.util.function.Predicate}
 */

@Deprecated
public interface Predicate<T>
{
    boolean test(T t);
}
