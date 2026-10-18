/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.web.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.querystore.SafeLog;
import org.openmrs.module.querystore.api.IndexStatus;
import org.openmrs.module.querystore.api.QueryResult;
import org.openmrs.module.querystore.api.QuerySpec;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.bootstrap.BootstrapService;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * BENCHMARK ONLY. Not for merge, and kept in a class of its own so it can be dropped in one piece:
 * the latency benchmark's {@code GET /querystore/search}, from commit 4f1a8ea, moved out of
 * {@link QueryStoreRestController} unchanged in behaviour. It carries the same error mapping (an
 * {@code @ExceptionHandler} applies only to its own controller).
 *
 * <p>Every projection it causes (a cold-touch through {@code searchByPatient}, or {@code cold=true}'s
 * reindex) holds one of the shared projection slots ({@code querystore.projection.maxConcurrent}),
 * and the patient reads as projecting meanwhile.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/querystore")
public class BenchSearchController {

	/**
	 * BENCHMARK ONLY. Not for merge. An HTTP wrapper over
	 * {@link QueryStoreService#searchByPatient(String, String, int)} for a latency benchmark.
	 *
	 * <pre>
	 * GET /ws/rest/v1/querystore/search?patient=&lt;uuid&gt;&amp;q=&lt;text&gt;&amp;limit=50&amp;cold=false
	 *   -&gt; [{"resourceType":"obs","resourceUuid":"...","date":"2024-01-31","text":"..."}, ...]
	 * </pre>
	 *
	 * <p>Makes the same checks as the patient endpoints, before any index call ({@link PatientRequests}):
	 * Get Patients; a {@code q} of at most {@value PatientRequests#MAX_QUERY_LENGTH} characters; a
	 * patient core shows the caller and not voided (404 otherwise, one answer for a bad shape, an
	 * unknown uuid and a voided patient); and only the types the caller could read through core. A
	 * caller who can read every type gets exactly the {@code searchByPatient} call the benchmark has
	 * always timed; one who cannot is searched through {@code query} over the readable types.
	 *
	 * <p>{@code limit} defaults to 50 and is clamped to 1..200. The {@code X-Querystore-Millis}
	 * response header is the wall time in ms of the index work: the search, plus with {@code cold}
	 * the re-projection. It excludes auth and JSON serialization.
	 *
	 * <p>{@code cold=true} requires {@code Manage Global Properties} and re-projects the patient from
	 * core before the search, through {@link QueryStoreService#reindex}: delete and re-projection
	 * under the per-patient lock, inside a projection slot, so it cannot interleave with another
	 * projection of the patient and counts against the projection bound. With no slot free in time it
	 * answers 503 with {@code Retry-After} and touches nothing. {@code X-Querystore-Reindex} carries
	 * the patient's status after it ({@code indexed} or {@code failed}).
	 *
	 * <p>It stays a GET, cold included, because the benchmark script calls it that way; a GET that
	 * re-projects is acceptable only because this controller ships on the benchmark build alone.
	 */
	@RequestMapping(value = "/search", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> benchSearch(@RequestParam(value = "patient", required = false) String patient,
	        @RequestParam(value = "q", required = false) String q,
	        @RequestParam(value = "limit", defaultValue = "50") int limit,
	        @RequestParam(value = "cold", defaultValue = "false") boolean cold) {
		// Up front, before anything else, so an under-privileged caller can never reach the cold path.
		Context.requirePrivilege(PrivilegeConstants.GET_PATIENTS);
		if (cold) {
			Context.requirePrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
		}

		String patientUuid = StringUtils.trimToNull(patient);
		if (patientUuid == null) {
			return errorResponse(HttpStatus.BAD_REQUEST, "patient is required");
		}
		String query = PatientRequests.requireQuery(q);
		PatientRequests.requirePatient(patientService(), patientUuid);
		List<String> types = PatientRequests.readableTypes(null, bootstrapService().getResourceTypeNames());
		int clampedLimit = Math.max(1, Math.min(200, limit));

		QueryStoreService service = queryStoreService();
		long start = System.nanoTime();
		IndexStatus reindexed = null;
		if (cold) {
			reindexed = service.reindex(patientUuid);
			if (reindexed == IndexStatus.NOT_INDEXED) {
				return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
				        .<Object> body(Collections.singletonMap("error", "No projection slot free; retry later"));
			}
		}
		List<QueryDocument> docs;
		if (types.isEmpty()) {
			docs = service.searchByPatient(patientUuid, query, clampedLimit);
		} else {
			docs = new ArrayList<QueryDocument>();
			for (QueryResult.Hit hit : service.query(QuerySpec.forPatient(patientUuid).query(query)
			        .mode(QuerySpec.Mode.HYBRID).types(types).limit(clampedLimit).build()).getHits()) {
				docs.add(hit.getDocument());
			}
		}
		long millis = (System.nanoTime() - start) / 1_000_000;

		List<Map<String, Object>> results = new ArrayList<Map<String, Object>>(docs.size());
		for (QueryDocument doc : docs) {
			Map<String, Object> row = new LinkedHashMap<String, Object>();
			row.put("resourceType", doc.getResourceType());
			row.put("resourceUuid", doc.getResourceUuid());
			row.put("date", doc.getDate() == null ? null : doc.getDate().toString());
			row.put("text", doc.getText());
			results.add(row);
		}
		ResponseEntity.BodyBuilder ok = ResponseEntity.ok().header("X-Querystore-Millis", String.valueOf(millis));
		if (reindexed != null) {
			ok.header("X-Querystore-Reindex", reindexed.wireName());
		}
		return ok.<Object> body(results);
	}

	/** Non-null only when tests inject them; production resolves each per call. */
	private PatientService injectedPatientService;

	private BootstrapService injectedBootstrapService;

	private PatientService patientService() {
		return injectedPatientService != null ? injectedPatientService : Context.getPatientService();
	}

	private BootstrapService bootstrapService() {
		return injectedBootstrapService != null ? injectedBootstrapService : Context.getService(BootstrapService.class);
	}

	void setPatientService(PatientService patientService) {
		this.injectedPatientService = patientService;
	}

	void setBootstrapService(BootstrapService bootstrapService) {
		this.injectedBootstrapService = bootstrapService;
	}

	@ExceptionHandler(BadParameterException.class)
	@ResponseBody
	public ResponseEntity<Object> handleBadParameter(BadParameterException e) {
		return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(PatientNotFoundException.class)
	@ResponseBody
	public ResponseEntity<Object> handlePatientNotFound(PatientNotFoundException e) {
		return errorResponse(HttpStatus.NOT_FOUND, "Patient not found");
	}

	/** Non-null only when a test injects the service; production resolves it per call. */
	private QueryStoreService injectedQueryStoreService;

	private QueryStoreService queryStoreService() {
		return injectedQueryStoreService != null ? injectedQueryStoreService
		        : Context.getService(QueryStoreService.class);
	}

	void setQueryStoreService(QueryStoreService queryStoreService) {
		this.injectedQueryStoreService = queryStoreService;
	}

	/** 401 when unauthenticated, 403 when a privilege is missing, as {@link QueryStoreRestController} does. */
	@ExceptionHandler({ ContextAuthenticationException.class, APIAuthenticationException.class })
	@ResponseBody
	public ResponseEntity<Object> handleAuthFailure(APIException e) {
		HttpStatus status = Context.isAuthenticated() ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
		return errorResponse(status,
		        status == HttpStatus.FORBIDDEN ? "Insufficient privileges" : "Authentication required");
	}

	/** A clean 500 rather than the framework's 200 with a stack trace; the detail is logged, not returned. */
	@ExceptionHandler(Exception.class)
	@ResponseBody
	public ResponseEntity<Object> handleUnexpected(Exception e) {
		SafeLog.error(BenchSearchController.class, "Unexpected error handling a benchmark search", e);
		return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
	}

	private static ResponseEntity<Object> errorResponse(HttpStatus status, String message) {
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("error", message);
		return new ResponseEntity<Object>(body, status);
	}
}
