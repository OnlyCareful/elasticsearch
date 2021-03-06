/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.cluster.metadata;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Tuple;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.metadata.IndexMetaData.INDEX_HIDDEN_SETTING;

/**
 * An index abstraction is a reference to one or more concrete indices.
 * An index abstraction has a unique name and encapsulates all the  {@link IndexMetaData} instances it is pointing to.
 * Also depending on type it may refer to a single or many concrete indices and may or may not have a write index.
 */
public interface IndexAbstraction {

    /**
     * @return the type of the index abstraction
     */
    Type getType();

    /**
     * @return the name of the index abstraction
     */
    String getName();

    /**
     * @return All {@link IndexMetaData} of all concrete indices this index abstraction is referring to.
     */
    List<IndexMetaData> getIndices();

    /**
     * A write index is a dedicated concrete index, that accepts all the new documents that belong to an index abstraction.
     *
     * A write index may also be a regular concrete index of a index abstraction and may therefore also be returned
     * by {@link #getIndices()}. An index abstraction may also not have a dedicated write index.
     *
     * @return the write index of this index abstraction or
     * <code>null</code> if this index abstraction doesn't have a write index.
     */
    @Nullable
    IndexMetaData getWriteIndex();

    /**
     * @return whether this index abstraction is hidden or not
     */
    boolean isHidden();

    /**
     * An index abstraction type.
     */
    enum Type {

        /**
         * An index abstraction that refers to a single concrete index.
         * This concrete index is also the write index.
         */
        CONCRETE_INDEX("concrete index"),

        /**
         * An index abstraction that refers to an alias.
         * An alias typically refers to many concrete indices and
         * may have a write index.
         */
        ALIAS("alias");

        private final String displayName;

        Type(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Represents an concrete index and encapsulates its {@link IndexMetaData}
     */
    class Index implements IndexAbstraction {

        private final IndexMetaData concreteIndex;

        public Index(IndexMetaData indexMetaData) {
            this.concreteIndex = indexMetaData;
        }

        @Override
        public String getName() {
            return concreteIndex.getIndex().getName();
        }

        @Override
        public Type getType() {
            return Type.CONCRETE_INDEX;
        }

        @Override
        public List<IndexMetaData> getIndices() {
            return List.of(concreteIndex);
        }

        @Override
        public IndexMetaData getWriteIndex() {
            return concreteIndex;
        }

        @Override
        public boolean isHidden() {
            return INDEX_HIDDEN_SETTING.get(concreteIndex.getSettings());
        }
    }

    /**
     * Represents an alias and groups all {@link IndexMetaData} instances sharing the same alias name together.
     */
    class Alias implements IndexAbstraction {

        private final String aliasName;
        private final List<IndexMetaData> referenceIndexMetaDatas;
        private final SetOnce<IndexMetaData> writeIndex = new SetOnce<>();
        private final boolean isHidden;

        public Alias(AliasMetaData aliasMetaData, IndexMetaData indexMetaData) {
            this.aliasName = aliasMetaData.getAlias();
            this.referenceIndexMetaDatas = new ArrayList<>();
            this.referenceIndexMetaDatas.add(indexMetaData);
            this.isHidden = aliasMetaData.isHidden() == null ? false : aliasMetaData.isHidden();
        }

        @Override
        public Type getType() {
            return Type.ALIAS;
        }

        public String getName() {
            return aliasName;
        }

        @Override
        public List<IndexMetaData> getIndices() {
            return referenceIndexMetaDatas;
        }


        @Nullable
        public IndexMetaData getWriteIndex() {
            return writeIndex.get();
        }

        @Override
        public boolean isHidden() {
            return isHidden;
        }

        /**
         * Returns the unique alias metadata per concrete index.
         *
         * (note that although alias can point to the same concrete indices, each alias reference may have its own routing
         * and filters)
         */
        public Iterable<Tuple<String, AliasMetaData>> getConcreteIndexAndAliasMetaDatas() {
            return () -> new Iterator<>() {

                int index = 0;

                @Override
                public boolean hasNext() {
                    return index < referenceIndexMetaDatas.size();
                }

                @Override
                public Tuple<String, AliasMetaData> next() {
                    IndexMetaData indexMetaData = referenceIndexMetaDatas.get(index++);
                    return new Tuple<>(indexMetaData.getIndex().getName(), indexMetaData.getAliases().get(aliasName));
                }
            };
        }

        public AliasMetaData getFirstAliasMetaData() {
            return referenceIndexMetaDatas.get(0).getAliases().get(aliasName);
        }

        void addIndex(IndexMetaData indexMetaData) {
            this.referenceIndexMetaDatas.add(indexMetaData);
        }

        public void computeAndValidateAliasProperties() {
            // Validate write indices
            List<IndexMetaData> writeIndices = referenceIndexMetaDatas.stream()
                .filter(idxMeta -> Boolean.TRUE.equals(idxMeta.getAliases().get(aliasName).writeIndex()))
                .collect(Collectors.toList());

            if (writeIndices.isEmpty() && referenceIndexMetaDatas.size() == 1
                    && referenceIndexMetaDatas.get(0).getAliases().get(aliasName).writeIndex() == null) {
                writeIndices.add(referenceIndexMetaDatas.get(0));
            }

            if (writeIndices.size() == 1) {
                writeIndex.set(writeIndices.get(0));
            } else if (writeIndices.size() > 1) {
                List<String> writeIndicesStrings = writeIndices.stream()
                    .map(i -> i.getIndex().getName()).collect(Collectors.toList());
                throw new IllegalStateException("alias [" + aliasName + "] has more than one write index [" +
                    Strings.collectionToCommaDelimitedString(writeIndicesStrings) + "]");
            }

            // Validate hidden status
            final Map<Boolean, List<IndexMetaData>> groupedByHiddenStatus = referenceIndexMetaDatas.stream()
                    .collect(Collectors.groupingBy(idxMeta -> Boolean.TRUE.equals(idxMeta.getAliases().get(aliasName).isHidden())));
            if (isNonEmpty(groupedByHiddenStatus.get(true)) && isNonEmpty(groupedByHiddenStatus.get(false))) {
                List<String> hiddenOn = groupedByHiddenStatus.get(true).stream()
                    .map(idx -> idx.getIndex().getName()).collect(Collectors.toList());
                List<String> nonHiddenOn = groupedByHiddenStatus.get(false).stream()
                    .map(idx -> idx.getIndex().getName()).collect(Collectors.toList());
                throw new IllegalStateException("alias [" + aliasName + "] has is_hidden set to true on indices [" +
                    Strings.collectionToCommaDelimitedString(hiddenOn) + "] but does not have is_hidden set to true on indices [" +
                    Strings.collectionToCommaDelimitedString(nonHiddenOn) + "]; alias must have the same is_hidden setting " +
                    "on all indices");
            }
        }

        private boolean isNonEmpty(List<IndexMetaData> idxMetas) {
            return (Objects.isNull(idxMetas) || idxMetas.isEmpty()) == false;
        }
    }
}
