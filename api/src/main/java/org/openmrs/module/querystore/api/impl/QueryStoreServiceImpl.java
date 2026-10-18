/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.api.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.SafeLog;
import org.openmrs.module.querystore.api.IndexStatus;
import org.openmrs.module.querystore.api.ProjectionProgress;
import org.openmrs.module.querystore.api.QueryResult;
import org.openmrs.module.querystore.api.QuerySpec;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.BackendStore;
import org.openmrs.module.querystore.backend.BulkWriteResult;
import org.openmrs.module.querystore.backend.DocFailure;
import org.openmrs.module.querystore.backend.Filter;
import org.openmrs.module.querystore.backend.SearchRequest;
import org.openmrs.module.querystore.backend.SearchResult;
import org.openmrs.module.querystore.backend.WriteResult;
import org.openmrs.module.querystore.bootstrap.BootstrapLauncher;
import org.openmrs.module.querystore.bootstrap.BootstrapService;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Default {@link QueryStoreService}. Delegates writes and search to the configured
 * {@link BackendStore}; hybrid fusion is the SPI's default-method responsibility (BM25 + kNN with
 * rank-based RRF — backends with native fusion may override; see ADR Decision 3 SPI sub-point 2).
 * The {@link EmbeddingProvider} dependency is optional: when null, search degrades to BM25-only.
 */
public class QueryStoreServiceImpl extends BaseOpenmrsService implements QueryStoreService {

	private static final Log log = LogFactory.getLog(QueryStoreServiceImpl.class);

	private BackendStore backend;

	private EmbeddingProvider embeddingProvider;

	// Test seam: when non-null, replaces the Context.getService(BootstrapService.class) lookup so
	// unit tests can pin the auto-index path without a live OpenMRS Spring context. Production
	// wiring leaves this null and resolves through Context — see ensureIndexedSafely. Package-private
	// to match the seam shape used in BootstrapServiceImpl.providersOverride.
	private BootstrapService bootstrapServiceOverride;

	// Query-embedding LRU. The ONNX query encoder dominates per-search latency on the hot path
	// (measured ~40-80 ms warm, ~3-4 s cold on the L6-v2 model). The same query string is reused
	// across patients within a session — UI default questions, repeat searches — so a small LRU
	// keyed by (modelName, query) eliminates the duplicate encode without changing retrieval
	// semantics. Bounded so a runaway caller cannot exhaust heap; access-ordered LinkedHashMap
	// under a synchronized wrapper is sufficient for the few-hundred-entries / few-QPS hot path.
	private static final int QUERY_EMBED_CACHE_MAX = 256;

	private final Map<String, float[]> queryEmbedCache = Collections.synchronizedMap(
	        new LinkedHashMap<String, float[]>(64, 0.75f, true) {
		        private static final long serialVersionUID = 1L;

		        @Override
		        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
			        return size() > QUERY_EMBED_CACHE_MAX;
		        }
	        });

	/** Default for {@code querystore.query.candidatePool}. */
	public static final int DEFAULT_CANDIDATE_POOL = 200;

	/** Largest candidate pool accepted, whatever the global property says: each request holds it in memory. */
	public static final int MAX_CANDIDATE_POOL = 1000;

	/** Default for {@code querystore.projection.syncWaitSeconds}. */
	public static final int DEFAULT_SYNC_PROJECTION_WAIT_SECONDS = 10;

	/** Longest synchronous wait honoured: the waiting request holds its server thread. */
	public static final int MAX_SYNC_PROJECTION_WAIT_SECONDS = 30;

	// Candidate pool for query(): its service-layer collapse, recency sort, facets and paging all run
	// over this many backend hits. Set from querystore.query.candidatePool at module start.
	private volatile int candidatePool = DEFAULT_CANDIDATE_POOL;

	// How long a synchronous cold-touch projection waits for a projection slot before serving what is
	// indexed. Set from querystore.projection.syncWaitSeconds at module start.
	private volatile long syncProjectionWaitMillis = DEFAULT_SYNC_PROJECTION_WAIT_SECONDS * 1000L;

	// [QSEVAL] lines carry clinical text, so they have a logger of their own outside this module's
	// package: DEBUG on org.openmrs.module.querystore, which troubleshooting turns on, leaves them off.
	private static final Log qsevalLog = LogFactory.getLog("querystore.qseval");

	int candidatePool() {
		return candidatePool;
	}

	public void setCandidatePool(int candidatePool) {
		this.candidatePool = Math.max(1, Math.min(MAX_CANDIDATE_POOL, candidatePool));
	}

	public void setSyncProjectionWaitMillis(long millis) {
		this.syncProjectionWaitMillis = Math.max(0, Math.min(MAX_SYNC_PROJECTION_WAIT_SECONDS * 1000L, millis));
	}

	// Optional: without it, query()/warm() can't start a background projection and getIndexStatus()
	// never reports PROJECTING. Wired in moduleApplicationContext.xml.
	private BootstrapLauncher bootstrapLauncher;

	public void setBootstrapLauncher(BootstrapLauncher bootstrapLauncher) {
		this.bootstrapLauncher = bootstrapLauncher;
		if (bootstrapLauncher != null) {
			bootstrapLauncher.setOnFailureForgotten(uuid -> dropForgottenChart(bootstrapLauncher, uuid));
		}
	}

	/**
	 * The launcher forgot that this patient's projection failed, so its chart, maybe partial, would
	 * read as indexed: drop it, so it reads not indexed and a later warm rebuilds it. Not while a
	 * projection of the patient runs: that one records its own outcome, and dropping its documents
	 * mid-run would leave a partial chart it then reports complete.
	 */
	private void dropForgottenChart(BootstrapLauncher launcher, String patientUuid) {
		if (!launcher.isWarming(patientUuid)) {
			bootstrapService().forgetPatient(patientUuid);
		}
	}

	public void setBackend(BackendStore backend) {
		this.backend = backend;
	}

	public void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
		this.embeddingProvider = embeddingProvider;
	}

	void setBootstrapServiceOverride(BootstrapService bootstrapService) {
		this.bootstrapServiceOverride = bootstrapService;
	}

	@Override
	public WriteResult index(QueryDocument document) {
		if (document == null) {
			return WriteResult.failed(new DocFailure(null, null, "document was null", false));
		}
		if (document.getResourceUuid() == null) {
			return WriteResult.failed(new DocFailure(document.getResourceType(), null,
			        "document resource_uuid was null", false));
		}
		if (backend == null) {
			// Misconfiguration, not a per-doc problem — silently swallowing here is what produced the
			// "bootstrap reports 682 indexed but the index holds 0" failure mode the slice fixes.
			// Throwing lets the bootstrap dispatcher (and any other caller that cares about persistence
			// accuracy) react: TypeBootstrapper.serializeAndEmbed catches RuntimeException to skip the
			// record, so the batch's documents_indexed only reflects confirmed writes.
			throw new IllegalStateException(
			        "No BackendStore wired into QueryStoreServiceImpl; cannot index "
			                + document.getResourceType() + "/" + document.getResourceUuid()
			                + ". Production: check wireBackend() in QueryStoreActivator.started() and "
			                + "the querystore.backend GP value. Tests: call setBackend() before "
			                + "exercising the index path.");
		}
		return backend.upsert(document);
	}

	// Overrides the interface's per-doc default with a single batched backend round-trip — the
	// commit-amortization win the bootstrap needs (ADR Decision 3).
	@Override
	public BulkWriteResult bulkIndex(List<QueryDocument> documents) {
		if (documents == null || documents.isEmpty()) {
			return new BulkWriteResult(0, 0, Collections.<DocFailure> emptyList());
		}
		if (backend == null) {
			// Same misconfiguration guard as index() — throw rather than silently report success.
			throw new IllegalStateException(
			        "No BackendStore wired into QueryStoreServiceImpl; cannot bulkIndex "
			                + documents.size() + " documents. Production: check wireBackend() in "
			                + "QueryStoreActivator.started() and the querystore.backend GP value. "
			                + "Tests: call setBackend() before exercising the index path.");
		}
		// Filter malformed docs into failures here rather than passing them to the backend, where a
		// single invalid doc would abort the whole batch (BackendDocs.validate throws outside the
		// backend's per-batch try/catch). Keeps one poison doc from sinking a good batch — the batched
		// analogue of index()'s null/uuid guards.
		List<QueryDocument> valid = new ArrayList<QueryDocument>(documents.size());
		List<DocFailure> invalid = new ArrayList<DocFailure>();
		for (QueryDocument doc : documents) {
			if (doc == null) {
				invalid.add(new DocFailure(null, null, "document was null", false));
			} else if (doc.getResourceUuid() == null) {
				invalid.add(new DocFailure(doc.getResourceType(), null, "document resource_uuid was null", false));
			} else {
				valid.add(doc);
			}
		}
		if (valid.isEmpty()) {
			return new BulkWriteResult(documents.size(), 0, invalid);
		}
		BulkWriteResult backendResult = backend.bulkUpsert(valid);
		if (invalid.isEmpty()) {
			return backendResult;
		}
		List<DocFailure> merged = new ArrayList<DocFailure>(backendResult.getFailures());
		merged.addAll(invalid);
		return new BulkWriteResult(documents.size(), backendResult.getSucceeded(), merged);
	}

	@Override
	public void delete(String resourceType, String resourceUuid) {
		if (resourceType == null || resourceUuid == null) {
			return;
		}
		if (backend == null) {
			return;
		}
		backend.delete(resourceType, resourceUuid);
	}

	@Override
	public void bulkDeleteByPatient(String patientUuid) {
		if (patientUuid == null) {
			return;
		}
		if (backend == null) {
			log.warn("No BackendStore wired; ignoring a bulkDeleteByPatient call");
			return;
		}
		backend.bulkDeleteByPatient(patientUuid);
	}

	@Override
	public List<QueryDocument> searchByPatient(String patientUuid, String query, int limit) {
		if (backend == null || patientUuid == null) {
			return Collections.emptyList();
		}
		if (!backend.existsByPatient(patientUuid)) {
			ensureIndexedSafely(patientUuid);
		}
		List<QueryDocument> hits = runHybrid(query, limit, Filter.patientScope(patientUuid));
		emitRetrievalLog(query, hits);
		return hits;
	}

	/**
	 * Retrieval-debug log: one {@code [QSEVAL]} line per hit at DEBUG level so the eval harness
	 * ({@code /tmp/qs_smoke_eval.py}) can grep its rubric against the actual retrieval order. DEBUG
	 * (not INFO) because the line emits patient record text — INFO would land PHI in production
	 * server logs by default. It has its own logger, {@code querystore.qseval}, outside the module's
	 * package, so operators running an eval pass enable DEBUG on that logger by name and DEBUG on the
	 * package does not turn it on; production pays nothing because the
	 * {@link Log#isDebugEnabled()} guard short-circuits before any string concatenation.
	 */
	private void emitRetrievalLog(String query, List<QueryDocument> hits) {
		if (!qsevalLog.isDebugEnabled() || hits == null || hits.isEmpty()) {
			return;
		}
		String safeQuery = query == null ? "" : query.replace('\n', ' ').replace('\r', ' ');
		for (int i = 0; i < hits.size(); i++) {
			QueryDocument d = hits.get(i);
			String text = d == null || d.getText() == null
					? "" : d.getText().replace('\n', ' ').replace('\r', ' ');
			qsevalLog.debug("[QSEVAL] q=[" + safeQuery + "] rank=" + (i + 1)
					+ " type=" + (d == null ? "null" : d.getResourceType())
					+ " uuid=" + (d == null ? "null" : d.getResourceUuid())
					+ " text=[" + text + "]");
		}
	}

	/** Lazy lookup avoids a Spring circular dependency: {@code BootstrapService} depends on
	 *  {@link QueryStoreService}, so resolving the reverse direction at wiring time would surface
	 *  the cycle in bean construction. Context lookup defers it to call time, when both beans are
	 *  fully wired. An unavailable service (no Spring context in test envs; narrow activation
	 *  window) throws from Context.getService and is absorbed by the catch — the search still runs
	 *  and returns whatever the backend has. */
	private void ensureIndexedSafely(String patientUuid) {
		try {
			// Through a projection slot, shared with background warms, so a burst of searches on cold
			// patients cannot run a projection each at once. With no slot free in time the search
			// serves what is indexed, as it does when projection fails.
			if (bootstrapLauncher == null) {
				bootstrapService().ensureIndexed(patientUuid);
			} else if (!bootstrapLauncher.runWithProjectionSlot(patientUuid,
			    () -> bootstrapService().projectIfNotIndexed(patientUuid), syncProjectionWaitMillis)) {
				log.debug("No projection slot for a cold patient in time; serving search with whatever is indexed");
			}
		}
		catch (RuntimeException e) {
			// Index-failure must not block search; whatever did get indexed (or what was already
			// present) is still searchable. Empty results are the same outcome as before this
			// feature shipped.
			SafeLog.warn(QueryStoreServiceImpl.class, "Auto-index for a cold patient failed; serving search with whatever is indexed", e);
			log.debug("Auto-index failed for patient " + patientUuid);
		}
	}

	private BootstrapService bootstrapService() {
		return bootstrapServiceOverride != null ? bootstrapServiceOverride : Context.getService(BootstrapService.class);
	}

	@Override
	public List<QueryDocument> search(String query, int limit) {
		if (backend == null) {
			return Collections.emptyList();
		}
		return runHybrid(query, limit, null);
	}

	@Override
	public List<QueryDocument> getPatientChart(String patientUuid) {
		if (backend == null || patientUuid == null) {
			return Collections.emptyList();
		}
		// Same cold-bootstrap protocol as searchByPatient: probe existsByPatient, lazy-project on
		// miss, then read. Decision 15 explicitly mirrors searchByPatient's behaviour here so the
		// first method to touch a never-indexed patient pays the projection cost once. The shared
		// ensureIndexedSafely also keeps the swallow-on-failure semantics consistent — an
		// index-failure must not block the LLM full-chart caller any more than it blocks search.
		if (!backend.existsByPatient(patientUuid)) {
			ensureIndexedSafely(patientUuid);
		}
		return backend.findAllByPatient(patientUuid);
	}

	@Override
	public IndexStatus getIndexStatus(String patientUuid) {
		if (backend == null || patientUuid == null) {
			return IndexStatus.NOT_INDEXED;
		}
		// Warming first: a background projection writes type by type, so existsByPatient turns true
		// long before the chart is complete.
		if (bootstrapLauncher != null && bootstrapLauncher.isWarming(patientUuid)) {
			return IndexStatus.PROJECTING;
		}
		// Failed before indexed: a warm that threw partway leaves a partial chart that exists.
		if (bootstrapLauncher != null && bootstrapLauncher.hasFailed(patientUuid)) {
			return IndexStatus.FAILED;
		}
		return backend.existsByPatient(patientUuid) ? IndexStatus.INDEXED : IndexStatus.NOT_INDEXED;
	}

	/**
	 * Deletes and re-projects the patient under its per-patient lock, inside a projection slot, waiting
	 * (bounded) for one and for any projection of the patient already in flight. A full re-projection,
	 * so success clears a recorded failure.
	 */
	@Override
	public IndexStatus reindex(String patientUuid) {
		if (backend == null || patientUuid == null) {
			return IndexStatus.NOT_INDEXED;
		}
		BooleanSupplier work = () -> {
			bootstrapService().reindexPatient(patientUuid);
			return true;
		};
		try {
			boolean ran = bootstrapLauncher != null
			        ? bootstrapLauncher.runExclusiveProjection(patientUuid, work, syncProjectionWaitMillis)
			        : work.getAsBoolean();
			return ran ? getIndexStatus(patientUuid) : IndexStatus.NOT_INDEXED;
		}
		catch (RuntimeException e) {
			SafeLog.warn(QueryStoreServiceImpl.class, "Patient reindex failed (patient uuid at DEBUG)", e);
			log.debug("Patient reindex failed for " + patientUuid);
			return IndexStatus.FAILED;
		}
	}

	@Override
	public long getRetryAfterMillis(String patientUuid) {
		return bootstrapLauncher == null ? 0 : bootstrapLauncher.millisUntilRetry(patientUuid);
	}

	@Override
	public IndexStatus warm(String patientUuid) {
		IndexStatus status = getIndexStatus(patientUuid);
		if (status == IndexStatus.PROJECTING || status == IndexStatus.INDEXED || backend == null
		        || patientUuid == null) {
			return status;
		}
		// A retry re-projects in full: the failed warm may have left a partial chart, which
		// ensureIndexed would take as indexed and skip. It waits out a backoff, so a chart that never
		// projects is not re-run by every keystroke or POST warm.
		boolean retry = status == IndexStatus.FAILED;
		if (retry && bootstrapLauncher != null && bootstrapLauncher.millisUntilRetry(patientUuid) > 0) {
			return status;
		}
		BooleanSupplier work = () -> {
			boolean full;
			if (retry) {
				bootstrapService().reindexPatient(patientUuid);
				full = true;
			} else {
				full = bootstrapService().projectIfNotIndexed(patientUuid);
			}
			// A live patient always projects at least its patient document, so nothing indexed means
			// the projection failed (or the uuid is no live patient's).
			if (!backend.existsByPatient(patientUuid)) {
				throw new IllegalStateException("Warm finished with nothing indexed");
			}
			return full;
		};
		return bootstrapLauncher != null && bootstrapLauncher.launchPatientWarm(patientUuid, work)
		        ? IndexStatus.PROJECTING : status;
	}

	/**
	 * Prototype: collapse, recency sort, facets and paging all run here, over a candidate pool of the
	 * top {@code querystore.query.candidatePool} backend hits (or the whole chart for a recency browse).
	 * Each has a
	 * backend-native form that would scale better, noted where it happens.
	 */
	@Override
	public QueryResult query(QuerySpec spec) {
		if (backend == null) {
			return QueryResult.empty(IndexStatus.NOT_INDEXED, spec);
		}
		requireKnownTypes(spec.getTypes());
		String patientUuid = spec.getPatientUuid();
		IndexStatus status = getIndexStatus(patientUuid);
		if (status == IndexStatus.FAILED && spec.isRetryFailed()) {
			// Retried by a warm once its backoff has run out (the omnibar's searches are the only warms
			// it gets), not on every keystroke. Until it re-projects, its chart may be partial.
			warm(patientUuid);
			status = getIndexStatus(patientUuid);
			if (status == IndexStatus.PROJECTING && !spec.isWait()) {
				// A retry is starting, and it deletes the chart before re-projecting it: the rows
				// there now are about to go. Answer as for a cold chart. A waiting search joins the
				// retry below and searches what it rebuilt.
				return spec.isPartialResults() ? QueryResult.empty(status, spec) : QueryResult.statusOnly(status);
			}
		}
		if (status != IndexStatus.INDEXED && status != IndexStatus.FAILED) {
			if (spec.isWait()) {
				// Waits (bounded) for a projection of its own, or for the one already in flight.
				ensureIndexedSafely(patientUuid);
			} else if (status == IndexStatus.NOT_INDEXED) {
				warm(patientUuid);
			}
			status = getIndexStatus(patientUuid);
		}
		// A projecting chart is served as far as it has landed, labelled projecting: the caller says
		// "still indexing", since a partial chart answers "no CD4" for obs that haven't landed yet. A
		// failed chart gets no hits (it may be partial, with nothing on its way to fill it), and a
		// chart with nothing indexed has none to give.
		if (status == IndexStatus.FAILED || status == IndexStatus.NOT_INDEXED) {
			return QueryResult.empty(status, spec);
		}
		if (status == IndexStatus.PROJECTING && !spec.isPartialResults()) {
			return QueryResult.statusOnly(status);
		}

		List<QueryResult.Hit> pool = candidates(spec);
		boolean faceted = spec.getFacets().contains(QuerySpec.Facet.RESOURCE_TYPE);
		// Hybrid's vector half returns its nearest documents however far they are, so it has no match
		// boundary: relevance order demotes the far ones, but a date sort or a count would not. Both
		// therefore run over the keyword matches only.
		List<QueryResult.Hit> matches = spec.getMode() == QuerySpec.Mode.HYBRID && spec.getQuery() != null
		        && (spec.getSort() == QuerySpec.Sort.RECENT || faceted) ? keywordMatches(spec, pool) : pool;
		if (spec.getSort() == QuerySpec.Sort.RECENT) {
			// Backend-native: a record_date sort on the search itself (ES "sort", Lucene Sort on the
			// RECORD_DATE doc value) instead of re-sorting a relevance-capped pool, which drops older
			// matches that ranked below the cap.
			pool = new ArrayList<>(matches);
			pool.sort(NEWEST_FIRST);
		}
		Map<String, Integer> typeCounts = faceted ? countByResourceType(matches) : null;

		List<QueryResult.Hit> hits;
		List<QueryResult.ConceptGroup> groups = null;
		if (spec.getCollapse() == QuerySpec.Collapse.CONCEPT) {
			groups = page(collapseByConcept(pool, spec), spec.getOffset(), spec.getLimit());
			hits = Collections.emptyList();
			List<QueryDocument> logged = new ArrayList<>(groups.size());
			groups.forEach(g -> logged.add(g.getLatest().getDocument()));
			emitRetrievalLog(spec.getQuery(), logged);
		} else {
			hits = page(pool, spec.getOffset(), spec.getLimit());
			List<QueryDocument> logged = new ArrayList<>(hits.size());
			hits.forEach(h -> logged.add(h.getDocument()));
			emitRetrievalLog(spec.getQuery(), logged);
		}
		QueryResult result = new QueryResult(status, hits, groups, typeCounts);
		return status == IndexStatus.PROJECTING ? result.withProgress(projectionProgress(patientUuid)) : result;
	}

	/** The patient's projection progress, or null when it can't be had: a hint, not worth failing a search over. */
	private ProjectionProgress projectionProgress(String patientUuid) {
		try {
			return bootstrapService().getProjectionProgress(patientUuid);
		}
		catch (RuntimeException e) {
			log.debug("Projection progress unavailable; answering without it", e);
			return null;
		}
	}

	/**
	 * Refuses a type no provider registers. The Lucene and MySQL backends create an index or table
	 * for any well-formed type a search names, so an unchecked name would create one.
	 */
	private void requireKnownTypes(List<String> types) {
		if (types.isEmpty()) {
			return;
		}
		List<String> known = bootstrapService().getResourceTypeNames();
		for (String type : types) {
			if (!known.contains(type)) {
				throw new IllegalArgumentException("Unknown resource type: " + type);
			}
		}
	}

	private List<QueryResult.Hit> candidates(QuerySpec spec) {
		List<QueryResult.Hit> pool = new ArrayList<>();
		if (spec.getQuery() == null) {
			// Recency browse: the whole chart, already newest first. Backend-native: a patient-scoped,
			// type-filtered top-N by record_date, instead of materializing the chart to keep N.
			for (QueryDocument d : backend.findAllByPatient(spec.getPatientUuid())) {
				if (spec.getTypes().isEmpty() || spec.getTypes().contains(d.getResourceType())) {
					pool.add(new QueryResult.Hit(d, null));
				}
			}
			return pool;
		}
		SearchRequest.Builder req = searchRequest(spec);
		SearchResult result;
		if (spec.getMode() == QuerySpec.Mode.KEYWORD) {
			result = backend.bm25(req.build());
		} else {
			if (embeddingProvider != null) {
				req.queryVector(embedQueryCached(spec.getQuery()));
			}
			result = backend.hybrid(req.build());
		}
		result.getHits().forEach(h -> pool.add(new QueryResult.Hit(h.getDocument(), h.getRawScore())));
		return pool;
	}

	private SearchRequest.Builder searchRequest(QuerySpec spec) {
		return SearchRequest.builder().queryText(spec.getQuery()).limit(candidatePool).resourceTypes(spec.getTypes())
		        .filter(Filter.patientScope(spec.getPatientUuid()));
	}

	/**
	 * The hybrid pool's hits that also match by keyword, in pool order. Costs a second BM25 pass
	 * (hybrid's own is internal to the backend). Backend-native: return the BM25 match set alongside
	 * the fused ranking, or apply a vector-similarity floor.
	 */
	private List<QueryResult.Hit> keywordMatches(QuerySpec spec, List<QueryResult.Hit> pool) {
		Set<String> matched = new HashSet<>();
		backend.bm25(searchRequest(spec).build()).getHits()
		        .forEach(h -> matched.add(h.getDocument().getResourceUuid()));
		List<QueryResult.Hit> out = new ArrayList<>();
		for (QueryResult.Hit h : pool) {
			if (matched.contains(h.getDocument().getResourceUuid())) {
				out.add(h);
			}
		}
		return out;
	}

	/**
	 * Groups the pool by {@code concept_uuid}, in order of each group's best-ranked member (or newest
	 * first under {@link QuerySpec.Sort#RECENT}). A group's latest, count and recent values come from
	 * the patient's whole chart rather than the pool, because the pool is ranked by relevance: the
	 * newest CD4 need not be among the top matches for "cd4".
	 *
	 * <p>Backend-native: ES field collapsing on {@code concept_uuid} with an {@code inner_hits} sorted
	 * by {@code record_date} (size 5) and a cardinality/terms aggregation for the count; Lucene
	 * grouping ({@code GroupingSearch} on a concept_uuid doc-values field) plus a date-sorted
	 * per-group read. Either removes the full-chart read this does per request.
	 */
	private List<QueryResult.ConceptGroup> collapseByConcept(List<QueryResult.Hit> pool, QuerySpec spec) {
		Map<String, List<QueryResult.Hit>> byKey = new LinkedHashMap<>();
		Map<String, Double> scoreByResource = new HashMap<>();
		boolean anyConcept = false;
		for (QueryResult.Hit h : pool) {
			QueryDocument d = h.getDocument();
			String concept = conceptUuid(d);
			anyConcept |= concept != null;
			String key = concept != null ? "c:" + concept : "d:" + d.getResourceType() + "/" + d.getResourceUuid();
			byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(h);
			if (h.getScore() != null) {
				scoreByResource.put(d.getResourceUuid(), h.getScore());
			}
		}

		Map<String, List<QueryDocument>> chartByConcept = anyConcept ? chartByConcept(spec, pool)
		        : Collections.<String, List<QueryDocument>> emptyMap();
		List<QueryResult.ConceptGroup> groups = new ArrayList<>(byKey.size());
		for (List<QueryResult.Hit> members : byKey.values()) {
			QueryDocument first = members.get(0).getDocument();
			String concept = conceptUuid(first);
			List<QueryDocument> recent = concept == null ? null : chartByConcept.get(concept);
			if (recent == null || recent.isEmpty()) {
				// No concept, or the chart read raced a write: fall back to the matching members.
				recent = new ArrayList<>(members.size());
				for (QueryResult.Hit m : members) {
					recent.add(m.getDocument());
				}
				recent.sort(NEWEST_DOC);
			}
			QueryDocument latest = recent.get(0);
			Double best = null;
			for (QueryResult.Hit m : members) {
				if (m.getScore() != null && (best == null || m.getScore() > best)) {
					best = m.getScore();
				}
			}
			Object name = latest.getMetadata().get(QueryStoreConstants.FIELD_CONCEPT_NAME);
			groups.add(new QueryResult.ConceptGroup(concept, name instanceof String ? (String) name : null,
			        recent.size(), best, new QueryResult.Hit(latest, scoreByResource.get(latest.getResourceUuid())),
			        recent));
		}
		if (spec.getSort() == QuerySpec.Sort.RECENT) {
			groups.sort((a, b) -> NEWEST_FIRST.compare(a.getLatest(), b.getLatest()));
		} else if (spec.getQuery() != null) {
			groups.sort(lookupOrder(spec.getQuery()));
		}
		return groups;
	}

	/**
	 * Relevance order for concept groups, adjusted for lookup: a concept whose name or a synonym is
	 * the query itself ("cd4" is a synonym of CD4 count, not of CD4%; "weight" is "Weight (kg)" less
	 * its units) comes first, then the best member's score. On a tied score, a group with a numeric
	 * value, then one with more readings. Ties stay in pool order (the sort is stable).
	 *
	 * <p>BM25 can't make this distinction: "CD4 count: 350", "CD4%: 21" and "Tests ordered: CD4
	 * panel" all match the one token "cd4" and score the same, and in hybrid mode CD4% is often the
	 * nearer vector. Measured on the O3 demo data, see the ADR's consumer-API open question.
	 */
	private static Comparator<QueryResult.ConceptGroup> lookupOrder(String query) {
		String wanted = normalizeName(query);
		return Comparator
		        .comparing((QueryResult.ConceptGroup g) -> !namesConcept(g.getLatest().getDocument(), wanted))
		        .thenComparing(QueryResult.ConceptGroup::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
		        .thenComparing(g -> g.getLatest().getDocument().getMetadata()
		                .get(QueryStoreConstants.FIELD_VALUE_NUMERIC) == null)
		        .thenComparing(QueryResult.ConceptGroup::getCount, Comparator.reverseOrder());
	}

	/** Whether the document's concept is called {@code wanted}, by its name or a synonym. */
	private static boolean namesConcept(QueryDocument d, String wanted) {
		if (wanted == null || conceptUuid(d) == null) {
			return false;
		}
		Object name = d.getMetadata().get(QueryStoreConstants.FIELD_CONCEPT_NAME);
		if (name instanceof String && wanted.equals(normalizeName((String) name))) {
			return true;
		}
		Object synonyms = d.getMetadata().get(QueryStoreConstants.FIELD_SYNONYMS);
		if (synonyms instanceof List) {
			for (Object synonym : (List<?>) synonyms) {
				if (synonym instanceof String && wanted.equals(normalizeName((String) synonym))) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Lower case, single-spaced, without a trailing parenthesized qualifier: CIEL puts units there
	 * ("Weight (kg)", "Temperature (c)"). Other punctuation stays, so "CD4%" is not "CD4".
	 */
	static String normalizeName(String name) {
		if (name == null) {
			return null;
		}
		String n = name.toLowerCase(Locale.ROOT).replaceAll("\\s*\\([^()]*\\)\\s*$", "").replaceAll("\\s+", " ").trim();
		return n.isEmpty() ? null : n;
	}

	/** The patient's chart (within the spec's types), newest first, indexed by concept. */
	private Map<String, List<QueryDocument>> chartByConcept(QuerySpec spec, List<QueryResult.Hit> pool) {
		// A recency browse's pool already is the chart within the spec's types; read it again only for a
		// search, whose pool holds just the matches.
		List<QueryDocument> chart;
		if (spec.getQuery() == null) {
			chart = new ArrayList<>(pool.size());
			pool.forEach(h -> chart.add(h.getDocument()));
		} else {
			chart = backend.findAllByPatient(spec.getPatientUuid());
		}
		Map<String, List<QueryDocument>> out = new HashMap<>();
		for (QueryDocument d : chart) {
			String concept = conceptUuid(d);
			if (concept != null && (spec.getTypes().isEmpty() || spec.getTypes().contains(d.getResourceType()))) {
				out.computeIfAbsent(concept, k -> new ArrayList<>()).add(d);
			}
		}
		out.values().forEach(docs -> docs.sort(NEWEST_DOC));
		return out;
	}

	/**
	 * Backend-native: ES terms aggregation on {@code _index} (or a resource_type keyword), Lucene
	 * per-index {@code IndexSearcher.count} with the same query. Both are exact and uncapped.
	 */
	private static Map<String, Integer> countByResourceType(List<QueryResult.Hit> pool) {
		Map<String, Integer> counts = new TreeMap<>();
		for (QueryResult.Hit h : pool) {
			counts.merge(h.getDocument().getResourceType(), 1, Integer::sum);
		}
		return counts;
	}

	private static String conceptUuid(QueryDocument d) {
		Object value = d.getMetadata().get(QueryStoreConstants.FIELD_CONCEPT_UUID);
		return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
	}

	private static <T> List<T> page(List<T> all, int offset, int limit) {
		if (offset >= all.size()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(all.subList(offset, Math.min(all.size(), offset + limit)));
	}

	// Newest record_date first, undated last. record_date is a day, so two records on the same day
	// (two CD4s entered for one visit date) tie; last_modified, when the record was entered or last
	// changed, breaks that tie, then the uuid so the order is stable.
	private static final Comparator<QueryDocument> NEWEST_DOC = Comparator
	        .comparing(QueryDocument::getDate, Comparator.nullsLast(Comparator.<LocalDate> reverseOrder()))
	        .thenComparing(QueryDocument::getLastModified, Comparator.nullsLast(Comparator.<Instant> reverseOrder()))
	        .thenComparing(QueryDocument::getResourceUuid, Comparator.nullsLast(Comparator.<String> naturalOrder()));

	// Used with a stable sort, so relevance breaks whatever NEWEST_DOC leaves tied.
	private static final Comparator<QueryResult.Hit> NEWEST_FIRST = Comparator.comparing(QueryResult.Hit::getDocument,
	    NEWEST_DOC);

	private List<QueryDocument> runHybrid(String query, int limit, Filter scope) {
		if (StringUtils.isBlank(query) || limit <= 0) {
			return Collections.emptyList();
		}
		SearchRequest.Builder req = SearchRequest.builder().queryText(query).limit(limit);
		if (scope != null) {
			req.filter(scope);
		}
		if (embeddingProvider != null) {
			req.queryVector(embedQueryCached(query));
		}
		return toDocuments(backend.hybrid(req.build()));
	}

	/**
	 * Returns the dense vector for {@code query}, reusing a previously-computed result when the
	 * same string has been embedded by the same model. Cache key includes the model name so an
	 * embedding-provider switch (see {@code querystore.embedding.providerBean} GP and
	 * {@link org.openmrs.module.querystore.embedding.ConfiguredEmbeddingProvider}) does not return
	 * a vector from the previous model. The cache is a small bounded LRU; queries beyond
	 * {@link #QUERY_EMBED_CACHE_MAX} oldest entries fall back to a live ONNX encode.
	 */
	private float[] embedQueryCached(String query) {
		String modelName = embeddingProvider.getModelName();
		String key = (modelName == null ? "" : modelName) + ' ' + query;
		float[] cached = queryEmbedCache.get(key);
		if (cached != null) {
			return cached;
		}
		float[] vec = embeddingProvider.embedQuery(query);
		if (vec != null) {
			queryEmbedCache.put(key, vec);
		}
		return vec;
	}

	private static List<QueryDocument> toDocuments(SearchResult result) {
		List<QueryDocument> out = new ArrayList<>(result.getHits().size());
		result.getHits().forEach(h -> out.add(h.getDocument()));
		return out;
	}
}
