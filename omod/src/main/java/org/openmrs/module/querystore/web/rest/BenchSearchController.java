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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.querystore.SafeLog;
import org.openmrs.module.querystore.api.QueryStoreService;
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
 * <p>Its cold-touch projection goes through {@code searchByPatient}, so it holds one of the shared
 * projection slots ({@code querystore.projection.maxConcurrent}) and the patient reads as projecting
 * meanwhile; with no slot free in {@code querystore.projection.syncWaitSeconds} it answers with what
 * is indexed.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/querystore")
public class BenchSearchController {

	/**
	 * BENCHMARK ONLY. Not for merge. A thin HTTP wrapper over
	 * {@link QueryStoreService#searchByPatient(String, String, int)} for a latency benchmark.
	 *
	 * <pre>
	 * GET /ws/rest/v1/querystore/search?patient=&lt;uuid&gt;&amp;q=&lt;text&gt;&amp;limit=50&amp;cold=false
	 *   -&gt; [{"resourceType":"obs","resourceUuid":"...","date":"2024-01-31","text":"..."}, ...]
	 * </pre>
	 *
	 * <p>{@code limit} defaults to 50 and is clamped to 1..200. The {@code X-Querystore-Millis}
	 * response header is the wall time in ms of the {@code searchByPatient} call alone, so it excludes
	 * auth, the cold delete and JSON serialization. With {@code cold=true} (which additionally requires
	 * {@code Manage Global Properties}) the patient's index documents are deleted first, so the search
	 * finds none and lazily re-projects the patient: the header then includes that re-projection but
	 * not the delete.
	 *
	 * <p>The cold delete runs outside the per-patient reindex lock, so a concurrent reindex or search
	 * for the same patient can observe a partial or empty chart. Restore a patient with
	 * {@code POST /reindex {"patient":"<uuid>"}}.
	 */
	@RequestMapping(value = "/search", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> benchSearch(@RequestParam(value = "patient", required = false) String patient,
	        @RequestParam(value = "q", required = false) String q,
	        @RequestParam(value = "limit", defaultValue = "50") int limit,
	        @RequestParam(value = "cold", defaultValue = "false") boolean cold) {
		// Up front, before any delete, so an under-privileged caller can never reach the cold path.
		Context.requirePrivilege(PrivilegeConstants.GET_PATIENTS);

		String patientUuid = StringUtils.trimToNull(patient);
		String query = StringUtils.trimToNull(q);
		if (patientUuid == null) {
			return errorResponse(HttpStatus.BAD_REQUEST, "patient is required");
		}
		if (query == null) {
			return errorResponse(HttpStatus.BAD_REQUEST, "q is required");
		}
		int clampedLimit = Math.max(1, Math.min(200, limit));

		QueryStoreService service = queryStoreService();
		if (cold) {
			Context.requirePrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
			service.bulkDeleteByPatient(patientUuid);
		}

		long start = System.nanoTime();
		List<QueryDocument> docs = service.searchByPatient(patientUuid, query, clampedLimit);
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
		return ResponseEntity.ok().header("X-Querystore-Millis", String.valueOf(millis)).<Object> body(results);
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
