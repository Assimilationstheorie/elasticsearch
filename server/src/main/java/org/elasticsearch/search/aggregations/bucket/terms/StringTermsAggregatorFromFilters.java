/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.aggregations.bucket.terms;

import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.common.CheckedFunction;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.AdaptingAggregator;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.CardinalityUpperBound;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalOrder;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation.Bucket;
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregator;
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilters;
import org.elasticsearch.search.aggregations.bucket.terms.GlobalOrdinalsStringTermsAggregator.OrdBucket;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregator.BucketCountThresholds;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

import static org.elasticsearch.search.aggregations.InternalOrder.isKeyOrder;

/**
 * Adapts a {@code terms} aggregation into a {@code filters} aggregation.
 * Its possible that we could lovingly hand craft an {@link Aggregator} that
 * uses similar tricks that'd have lower memory overhead but generally we reach
 * for this when we know that there aren't many distinct values so it doesn't
 * have particularly high memory cost anyway. And we expect to be able to
 * further optimize the {@code filters} aggregation in more cases than a
 * special purpose {@code terms} aggregator.
 */
public class StringTermsAggregatorFromFilters extends AdaptingAggregator {
    static StringTermsAggregatorFromFilters adaptIntoFiltersOrNull(
        String name,
        AggregatorFactories factories,
        AggregationContext context,
        Aggregator parent,
        boolean showTermDocCountError,
        CardinalityUpperBound cardinality,
        Map<String, Object> metadata,
        ValuesSourceConfig valuesSourceConfig,
        BucketOrder order,
        BucketCountThresholds bucketCountThresholds,
        LongPredicate acceptedOrds,
        SortedSetDocValues values
    ) throws IOException {
        if (false == valuesSourceConfig.alignesWithSearchIndex()) {
            return null;
        }
        if (false == FiltersAggregator.canUseFilterByFilter(parent, factories, null)) {
            return null;
        }
        List<String> keys = new ArrayList<>();
        List<Query> filters = new ArrayList<>();
        TermsEnum terms = values.termsEnum();
        for (long ord = 0; ord < values.getValueCount(); ord++) {
            if (acceptedOrds.test(ord) == false) {
                continue;
            }
            terms.seekExact(ord);
            keys.add(Long.toString(ord));
            /*
             * It *feels* like there should be a query that operates
             * directly on the global ordinals but there isn't. Building
             * one would be tricky because there isn't mapping from
             * every global ordinal to its segment ordinal - only from
             * the segment ordinal to the global ordinal. You could
             * search the mapping to get it but, like I said, tricky.
             */
            TermQueryBuilder b = new TermQueryBuilder(
                valuesSourceConfig.fieldContext().field(),
                valuesSourceConfig.format().format(terms.term())
            );
            filters.add(context.buildQuery(b));
        }
        StringTermsAggregatorFromFilters adapted = new StringTermsAggregatorFromFilters(
            parent,
            factories,
            subAggs -> FiltersAggregator.buildFilterByFilter(
                name,
                subAggs,
                keys.toArray(new String[0]),
                filters.toArray(new Query[0]),
                false,
                null,
                context,
                parent,
                cardinality,
                metadata
            ),
            showTermDocCountError,
            valuesSourceConfig.format(),
            order,
            bucketCountThresholds,
            terms
        );
        return adapted.delegate() == null ? null : adapted;
    }

    private final boolean showTermDocCountError;
    private final DocValueFormat format;
    private final BucketOrder order;
    private final BucketCountThresholds bucketCountThresholds;
    private final TermsEnum terms;

    public StringTermsAggregatorFromFilters(
        Aggregator parent,
        AggregatorFactories subAggregators,
        CheckedFunction<AggregatorFactories, Aggregator, IOException> delegate,
        boolean showTermDocCountError,
        DocValueFormat format,
        BucketOrder order,
        BucketCountThresholds bucketCountThresholds,
        TermsEnum terms
    ) throws IOException {
        super(parent, subAggregators, delegate);
        this.showTermDocCountError = showTermDocCountError;
        this.format = format;
        this.order = order;
        this.bucketCountThresholds = bucketCountThresholds;
        this.terms = terms;
    }

    @Override
    protected InternalAggregation adapt(InternalAggregation delegateResult) throws IOException {
        InternalFilters filters = (InternalFilters) delegateResult;
        List<StringTerms.Bucket> buckets;
        long otherDocsCount = 0;
        BucketOrder reduceOrder = isKeyOrder(order) ? order : InternalOrder.key(true);
        if (filters.getBuckets().size() > bucketCountThresholds.getShardSize()) {
            PriorityQueue<OrdBucket> queue = new PriorityQueue<OrdBucket>(bucketCountThresholds.getShardSize()) {
                private final Comparator<Bucket> comparator = order.comparator();

                @Override
                protected boolean lessThan(OrdBucket a, OrdBucket b) {
                    return comparator.compare(a, b) > 0;
                }
            };
            OrdBucket spare = null;
            for (InternalFilters.InternalBucket b : filters.getBuckets()) {
                if (b.getDocCount() < bucketCountThresholds.getShardMinDocCount()) {
                    continue;
                }
                if (spare == null) {
                    spare = new OrdBucket(showTermDocCountError, format);
                } else {
                    otherDocsCount += spare.docCount;
                }
                spare.globalOrd = Long.parseLong(b.getKey());
                spare.docCount = b.getDocCount();
                spare.aggregations = b.getAggregations();
                spare = queue.insertWithOverflow(spare);
            }
            if (spare != null) {
                otherDocsCount += spare.docCount;
            }
            buckets = new ArrayList<>(queue.size());
            if (isKeyOrder(order) == false) {
                for (OrdBucket b : queue) {
                    buckets.add(buildBucket(b));
                }
                Collections.sort(buckets, reduceOrder.comparator());
            } else {
                /*
                 * Note for the curious: you can just use a for loop to iterate
                 * the PriorityQueue to get all of the buckets. But they don't
                 * come off in order. This gets them in order. It's also O(n*log(n))
                 * instead of O(n), but such is life. And n shouldn't be too big.
                 */
                while (queue.size() > 0) {
                    buckets.add(buildBucket(queue.pop()));
                }
                // The buckets come off last to first so we need to flip them.
                Collections.reverse(buckets);
            }
        } else {
            buckets = new ArrayList<>(filters.getBuckets().size());
            for (InternalFilters.InternalBucket b : filters.getBuckets()) {
                if (b.getDocCount() < bucketCountThresholds.getShardMinDocCount()) {
                    continue;
                }
                buckets.add(buildBucket(b));
            }
            Collections.sort(buckets, reduceOrder.comparator());
        }
        return new StringTerms(
            filters.getName(),
            reduceOrder,
            order,
            bucketCountThresholds.getRequiredSize(),
            bucketCountThresholds.getMinDocCount(),
            filters.getMetadata(),
            format,
            bucketCountThresholds.getShardSize(),
            showTermDocCountError,
            otherDocsCount,
            buckets,
            0
        );
    }

    private StringTerms.Bucket buildBucket(OrdBucket b) throws IOException {
        terms.seekExact(b.globalOrd);
        return new StringTerms.Bucket(BytesRef.deepCopyOf(terms.term()), b.getDocCount(), b.aggregations, showTermDocCountError, 0, format);
    }

    private StringTerms.Bucket buildBucket(InternalFilters.InternalBucket b) throws IOException {
        terms.seekExact(Long.parseLong(b.getKey()));
        return new StringTerms.Bucket(
            BytesRef.deepCopyOf(terms.term()),
            b.getDocCount(),
            b.getAggregations(),
            showTermDocCountError,
            0,
            format
        );
    }
}
