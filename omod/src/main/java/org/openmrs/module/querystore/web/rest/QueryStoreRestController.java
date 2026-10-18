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
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.querystore.SafeLog;
import org.openmrs.module.querystore.api.IndexStatus;
import org.openmrs.module.querystore.api.QueryResult;
import org.openmrs.module.querystore.api.QuerySpec;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.bootstrap.BootstrapLauncher;
import org.openmrs.module.querystore.bootstrap.BootstrapService;
import org.openmrs.module.querystore.bootstrap.BootstrapStatusReport;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * REST surface for the query store. Currently exposes the bootstrap (initial-backfill) status so
 * operators and deploy pipelines can verify a deployment is <em>fully indexed</em> before trusting
 * its reads — a SQL-dump-seeded deployment bypasses the live indexing path and depends on the
 * background bootstrap completing, and the lazy per-patient projection cannot repair an
 * already-partially-indexed patient.
 *
 * <p>It also exposes a reindex trigger so a stale/partially-indexed instance can be repaired without
 * restarting the server (the only other no-restart path, a cold-touch lazy projection, refuses to
 * touch an already-partially-indexed patient). It reindexes one patient synchronously, or — given an
 * explicit {@code scope:"all"} — launches the global bootstrap over every patient asynchronously,
 * since a full-corpus scan cannot run in a request thread.
 *
 * <pre>
 * GET  /ws/rest/v1/querystore/indexingstatus
 *   -&gt; {"complete": false, "types": [{"resourceType":"obs","status":"RUNNING",...}, ...]}
 * POST /ws/rest/v1/querystore/reindex   {"patient":"&lt;uuid&gt;"}
 *   -&gt; 200 {"patient":"&lt;uuid&gt;", "documentsIndexed": 154}
 * POST /ws/rest/v1/querystore/reindex   {"scope":"all"}
 *   -&gt; 202 {"accepted": true}   (then poll indexingstatus)
 *
 * POST /ws/rest/v1/querystore/patient/{uuid}/warm     -&gt; 202 {"status": "projecting"}
 * GET  /ws/rest/v1/querystore/patient/{uuid}/status   -&gt; {"status": "indexed"}
 * GET  /ws/rest/v1/querystore/patient/{uuid}/recent?limit=5&amp;types=obs
 * GET  /ws/rest/v1/querystore/patient/{uuid}/search?q=cd4&amp;mode=keyword&amp;collapse=concept&amp;limit=5&amp;wait=false
 * </pre>
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/querystore")
public class QueryStoreRestController {

	private static final Logger log = LoggerFactory.getLogger(QueryStoreRestController.class);

	/**
	 * Per-resource-type bootstrap status plus a derived {@code complete} flag. Gated by
	 * {@code Get Patients} — the same privilege the query store's read API requires — because a
	 * type's {@code failureMessage} can contain internal record/patient identifiers.
	 */
	@RequestMapping(value = "/indexingstatus", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> getIndexingStatus() {
		Context.requirePrivilege(PrivilegeConstants.GET_PATIENTS);

		BootstrapStatusReport report = BootstrapStatusReport.from(
				Context.getService(BootstrapService.class).getStatus());

		// Response shape (keys + values) is produced and unit-tested in BootstrapStatusReport.toMap();
		// the controller stays a thin adapter so the JSON contract isn't hand-typed untested here.
		return new ResponseEntity<Object>(report.toMap(), HttpStatus.OK);
	}

	/**
	 * Per-type drift snapshot — core "expected" count vs live index count (ADR: Sync reliability and
	 * reconciliation). Detection only; the operator reuses {@code POST /reindex} to remediate a gap.
	 * Gated by {@code Manage Global Properties} (not the read endpoint's {@code Get Patients}): it runs
	 * a COUNT per resource type across core and the index, so it is an admin/ops reconciliation tool,
	 * the same audience as {@code reindex}. The JSON shape is produced and unit-tested in
	 * {@link org.openmrs.module.querystore.bootstrap.DriftReport#toMap()}; the controller is a thin adapter.
	 */
	@RequestMapping(value = "/drift", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> getDrift() {
		Context.requirePrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
		return new ResponseEntity<Object>(bootstrapService().getDrift().toMap(), HttpStatus.OK);
	}

	/**
	 * Reindexes the read store, in one of three scopes selected by the request body:
	 *
	 * <ul>
	 * <li>{@code {"patient":"<uuid>"}} — force a full re-projection of one patient (delete + re-index
	 * every type), then report the resulting document count. <strong>Synchronous</strong>: bounded to
	 * one patient and runs in the authenticated request thread. The delete-before-reindex invariant
	 * lives in {@link BootstrapService#reindexPatient} (unit-tested) so this stays a thin adapter.</li>
	 * <li>{@code {"scope":"all"}} — launch the global bootstrap over every patient and type.
	 * <strong>Asynchronous</strong>: a full-corpus scan cannot run in a request thread, so it is handed
	 * to a daemon thread via {@link BootstrapLauncher} and the call returns {@code 202 Accepted}
	 * immediately. Progress is observed via {@code GET /querystore/indexingstatus}.</li>
	 * <li>{@code {"scope":"type","resourceType":"<type>"}} — reconciliation remediation: force a full
	 * re-walk of one resource type to correct drift surfaced by {@code GET /querystore/drift} (ADR:
	 * Sync reliability and reconciliation). <strong>Asynchronous</strong> (a full single-type scan
	 * can't run in a request thread); returns {@code 202 Accepted}, poll {@code indexingstatus}.
	 * Re-walk only — corrects under-indexing, not stale extras (see {@link BootstrapService#resyncType}).
	 * The {@code resourceType} is validated against {@link BootstrapService#getResourceTypeNames()}
	 * (a {@code 400} for an unknown type) so a typo fails fast rather than silently on the daemon
	 * thread.</li>
	 * </ul>
	 *
	 * <p>The global/type scopes are opt-in via an explicit {@code scope} rather than "absent patient
	 * means everything": absence overlaps with a malformed/truncated request, and resolving that
	 * ambiguity by launching the single most expensive operation in the module is a footgun. A request
	 * with neither {@code patient} nor a {@code scope} is therefore a {@code 400}, and an
	 * unrecognised {@code scope} is rejected rather than silently falling through.
	 *
	 * <p>Gated by {@code Manage Global Properties} rather than the read endpoint's {@code Get Patients}:
	 * both scopes are destructive/expensive maintenance operations, so they require a system-
	 * administration privilege.
	 *
	 * <p>Eventual-consistency note (per-patient scope): the delete and the re-projection run under a
	 * per-patient lock, but a concurrent search for the <em>same</em> patient may briefly observe
	 * partial or empty results while the rebuild is in flight (its existence probe runs outside that
	 * lock). The full chart is restored by the time this call returns. Triggering a reindex during
	 * active charting on that patient is the caller's call.
	 */
	@RequestMapping(value = "/reindex", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> reindex(@RequestBody(required = false) Map<String, String> body) {
		Context.requirePrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);

		String scope = StringUtils.trimToNull(body == null ? null : body.get("scope"));
		String patientUuid = StringUtils.trimToNull(body == null ? null : body.get("patient"));
		String resourceType = StringUtils.trimToNull(body == null ? null : body.get("resourceType"));

		if (scope != null) {
			if ("all".equalsIgnoreCase(scope)) {
				if (patientUuid != null || resourceType != null) {
					// Ambiguous intent: a global reindex is the most expensive op in the module, so do
					// not silently let scope win and ignore a patient/resourceType the caller also sent.
					return errorResponse(HttpStatus.BAD_REQUEST,
					    "Specify only one of patient, scope:\"all\", or scope:\"type\" + resourceType");
				}
				boolean launched = bootstrapLauncher().launchAsync();
				if (!launched) {
					// No daemon token wired yet, so the async backfill cannot start. 503 rather than a
					// misleading 202 that would imply work is underway.
					return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
					    "Cannot start reindex: the bootstrap daemon is not yet available");
				}
				Map<String, Object> result = new HashMap<String, Object>();
				result.put("accepted", true);
				return new ResponseEntity<Object>(result, HttpStatus.ACCEPTED);
			}
			if ("type".equalsIgnoreCase(scope)) {
				// Reconciliation remediation: re-walk one drifted type (ADR: Sync reliability and
				// reconciliation). Async like scope:"all" — a full single-type scan can't run in a
				// request thread.
				if (patientUuid != null) {
					return errorResponse(HttpStatus.BAD_REQUEST, "Specify either patient or scope:\"type\", not both");
				}
				if (resourceType == null) {
					return errorResponse(HttpStatus.BAD_REQUEST, "scope:\"type\" requires a resourceType");
				}
				// Validate synchronously (a cheap registry read) so a typo is a 400 here rather than a
				// 202 that fails silently on the daemon thread.
				if (!bootstrapService().getResourceTypeNames().contains(resourceType)) {
					return errorResponse(HttpStatus.BAD_REQUEST, "Unknown resourceType '" + resourceType + "'");
				}
				boolean launched = bootstrapLauncher().launchResyncAsync(resourceType);
				if (!launched) {
					return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
					    "Cannot start re-sync: the bootstrap daemon is not yet available");
				}
				Map<String, Object> result = new HashMap<String, Object>();
				result.put("accepted", true);
				result.put("resourceType", resourceType);
				return new ResponseEntity<Object>(result, HttpStatus.ACCEPTED);
			}
			return errorResponse(HttpStatus.BAD_REQUEST,
			    "Unknown scope '" + scope + "'; supported scopes are \"all\" and \"type\"");
		}

		if (patientUuid == null) {
			return errorResponse(HttpStatus.BAD_REQUEST, "patient or scope (\"all\" or \"type\") is required");
		}

		bootstrapService().reindexPatient(patientUuid);

		int documentsIndexed = queryStoreService().getPatientChart(patientUuid).size();
		Map<String, Object> result = new HashMap<String, Object>();
		result.put("patient", patientUuid);
		result.put("documentsIndexed", documentsIndexed);
		return new ResponseEntity<Object>(result, HttpStatus.OK);
	}

	// ---------- patient-scoped consumer endpoints (omnibar prototype) ----------
	//
	// The patient is always a path segment: there is no cross-patient search and no full-chart
	// endpoint here (recent is capped at MAX_PAGE). Gated by Get Patients, the privilege the service
	// methods themselves declare via @Authorized, and by core's own read of the patient (requirePatient).

	/** Largest page the REST surface serves, so {@code recent} can't become a full-chart dump. */
	static final int MAX_PAGE = 50;

	/** Longest query text accepted: type-ahead and questions are short, and hybrid mode encodes it. */
	static final int MAX_QUERY_LENGTH = 500;

	/**
	 * Starts projecting a never-indexed patient in the background and returns at once with
	 * {@code 202 {"status": "projecting" | "not_indexed"}}, or {@code 200 {"status": "indexed"}} when
	 * there was nothing to do. Idempotent: an indexed or already-projecting patient starts nothing. {@code not_indexed} means no warm could start (the
	 * daemon isn't up, or the concurrent-warm cap is reached); the caller may retry.
	 */
	@RequestMapping(value = "/patient/{patientUuid}/warm", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> warm(@PathVariable("patientUuid") String patientUuid) {
		Context.requirePrivilege(PrivilegeConstants.GET_PATIENTS);
		requirePatient(patientUuid);
		IndexStatus status = queryStoreService().warm(patientUuid);
		return statusResponse(status, status == IndexStatus.INDEXED ? HttpStatus.OK : HttpStatus.ACCEPTED);
	}

	/** {@code {"status": "indexed" | "projecting" | "not_indexed"}}. */
	@RequestMapping(value = "/patient/{patientUuid}/status", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> status(@PathVariable("patientUuid") String patientUuid) {
		Context.requirePrivilege(PrivilegeConstants.GET_PATIENTS);
		requirePatient(patientUuid);
		return statusResponse(queryStoreService().getIndexStatus(patientUuid), HttpStatus.OK);
	}

	/**
	 * The patient's newest documents: {@code {"status", "hits": [...]}}. Doesn't block on a cold
	 * patient unless {@code wait=true}; it warms one and answers {@code projecting} with no hits.
	 */
	@RequestMapping(value = "/patient/{patientUuid}/recent", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> recent(@PathVariable("patientUuid") String patientUuid,
	        @RequestParam(value = "limit", required = false) Integer limit,
	        @RequestParam(value = "types", required = false) String types,
	        @RequestParam(value = "wait", required = false, defaultValue = "false") boolean wait) {
		Context.requirePrivilege(PrivilegeConstants.GET_PATIENTS);
		requirePatient(patientUuid);
		QuerySpec spec = QuerySpec.forPatient(patientUuid).sort(QuerySpec.Sort.RECENT).types(knownTypes(types))
		        .limit(pageSize(limit, 5)).wait(wait).build();
		return new ResponseEntity<Object>(queryStoreService().query(spec).toMap(), HttpStatus.OK);
	}

	/**
	 * Patient-scoped search; see {@link QuerySpec} for what each parameter does. The body is
	 * {@link QueryResult#toMap()}: {@code status}, {@code hits}, plus {@code groups} when
	 * {@code collapse=concept} and {@code facets} when {@code facets=resourceType}. Defaults:
	 * {@code mode=hybrid}, {@code sort=relevance}, {@code collapse=none}, {@code limit=10},
	 * {@code offset=0}, {@code wait=true}. An unknown value for any of them is a {@code 400}.
	 */
	@RequestMapping(value = "/patient/{patientUuid}/search", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> search(@PathVariable("patientUuid") String patientUuid,
	        @RequestParam(value = "q", required = false) String q,
	        @RequestParam(value = "mode", required = false) String mode,
	        @RequestParam(value = "types", required = false) String types,
	        @RequestParam(value = "sort", required = false) String sort,
	        @RequestParam(value = "collapse", required = false) String collapse,
	        @RequestParam(value = "facets", required = false) String facets,
	        @RequestParam(value = "limit", required = false) Integer limit,
	        @RequestParam(value = "offset", required = false) Integer offset,
	        @RequestParam(value = "wait", required = false, defaultValue = "true") boolean wait) {
		Context.requirePrivilege(PrivilegeConstants.GET_PATIENTS);
		requirePatient(patientUuid);
		if (StringUtils.isBlank(q)) {
			throw new BadParameterException("q is required");
		}
		if (q.length() > MAX_QUERY_LENGTH) {
			throw new BadParameterException("q must be at most " + MAX_QUERY_LENGTH + " characters");
		}
		QuerySpec.Builder spec = QuerySpec.forPatient(patientUuid).query(q)
		        .mode(parse(QuerySpec.Mode.class, "mode", mode, QuerySpec.Mode.HYBRID))
		        .sort(parse(QuerySpec.Sort.class, "sort", sort, QuerySpec.Sort.RELEVANCE))
		        .collapse(parse(QuerySpec.Collapse.class, "collapse", collapse, QuerySpec.Collapse.NONE))
		        .types(knownTypes(types)).limit(pageSize(limit, QuerySpec.DEFAULT_LIMIT)).wait(wait);
		if (offset != null) {
			if (offset < 0) {
				throw new BadParameterException("offset must not be negative");
			}
			spec.offset(offset);
		}
		for (String facet : splitTypes(facets)) {
			if (!"resourceType".equals(facet)) {
				throw new BadParameterException("Unknown facet '" + facet + "'; supported: resourceType");
			}
			spec.facet(QuerySpec.Facet.RESOURCE_TYPE);
		}
		return new ResponseEntity<Object>(queryStoreService().query(spec.build()).toMap(), HttpStatus.OK);
	}

	/**
	 * 404 unless core shows this caller the patient, and it is not voided. The lookup goes through core's own patient read,
	 * so whatever patient-level restriction a deployment applies there applies here too (ADR Decision
	 * 14 leaves querystore's own checks at the privilege), and a uuid that is no patient's never
	 * reaches the warm machinery, whose per-patient bookkeeping would otherwise grow with every uuid a
	 * caller tries. A 404 tells the caller no more than core's {@code GET /patient/{uuid}} does.
	 */
	private void requirePatient(String patientUuid) {
		// getPatientByUuid returns voided patients too; a voided patient has no read-store presence
		// (ADR Decision 10), so its warm could only ever fail.
		Patient patient = patientService().getPatientByUuid(patientUuid);
		if (patient == null || Boolean.TRUE.equals(patient.getVoided())) {
			throw new PatientNotFoundException();
		}
	}

	/**
	 * The {@code types} parameter, each checked against the registered resource types. An unknown
	 * name is a 400 rather than a search: the Lucene and MySQL backends create an index or table for
	 * any well-formed type a search names.
	 */
	private List<String> knownTypes(String csv) {
		List<String> types = splitTypes(csv);
		if (!types.isEmpty()) {
			List<String> known = bootstrapService().getResourceTypeNames();
			for (String type : types) {
				if (!known.contains(type)) {
					throw new BadParameterException("Unknown type '" + StringUtils.abbreviate(type, 64) + "'");
				}
			}
		}
		return types;
	}

	private static ResponseEntity<Object> statusResponse(IndexStatus status, HttpStatus httpStatus) {
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("status", status.wireName());
		return new ResponseEntity<Object>(body, httpStatus);
	}

	private static int pageSize(Integer limit, int defaultLimit) {
		if (limit == null) {
			return defaultLimit;
		}
		if (limit <= 0 || limit > MAX_PAGE) {
			throw new BadParameterException("limit must be between 1 and " + MAX_PAGE);
		}
		return limit;
	}

	private static List<String> splitTypes(String csv) {
		List<String> out = new ArrayList<String>();
		if (csv != null) {
			for (String part : csv.split(",")) {
				if (StringUtils.isNotBlank(part)) {
					out.add(part.trim());
				}
			}
		}
		return out;
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String name, String value, E defaultValue) {
		if (StringUtils.isBlank(value)) {
			return defaultValue;
		}
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equalsIgnoreCase(value.trim())) {
				return constant;
			}
		}
		throw new BadParameterException("Unknown " + name + " '" + value + "'");
	}

	/** A request parameter the endpoint can't honour; its message is safe to return to the caller. */
	static final class BadParameterException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		BadParameterException(String message) {
			super(message);
		}
	}

	/** The patient in the path is not one core shows this caller. */
	static final class PatientNotFoundException extends RuntimeException {

		private static final long serialVersionUID = 1L;
	}

	@ExceptionHandler(PatientNotFoundException.class)
	@ResponseBody
	public ResponseEntity<Object> handlePatientNotFound(PatientNotFoundException e) {
		return errorResponse(HttpStatus.NOT_FOUND, "Patient not found");
	}

	@ExceptionHandler(BadParameterException.class)
	@ResponseBody
	public ResponseEntity<Object> handleBadParameter(BadParameterException e) {
		return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	/** Non-null only when a test injects a launcher via {@link #setBootstrapLauncher}; production
	 *  leaves this null and resolves the singleton from the Spring context per call. */
	private BootstrapLauncher injectedBootstrapLauncher;

	/** Resolves the launcher: the test-injected one if present, else the Spring-context singleton. */
	private BootstrapLauncher bootstrapLauncher() {
		return injectedBootstrapLauncher != null ? injectedBootstrapLauncher
		        : Context.getRegisteredComponent("querystore.bootstrap.launcher", BootstrapLauncher.class);
	}

	/** Visible-for-testing seam: lets the POJO controller test exercise the scope:"all" routing
	 *  without a Spring context. Production resolves the launcher via {@link Context}. */
	void setBootstrapLauncher(BootstrapLauncher bootstrapLauncher) {
		this.injectedBootstrapLauncher = bootstrapLauncher;
	}

	/** Non-null only when a test injects collaborators; production leaves these null and resolves
	 *  the services from the Spring context per call (keeping the controller a thin adapter). */
	private BootstrapService injectedBootstrapService;

	private QueryStoreService injectedQueryStoreService;

	private PatientService injectedPatientService;

	private PatientService patientService() {
		return injectedPatientService != null ? injectedPatientService : Context.getPatientService();
	}

	void setPatientService(PatientService patientService) {
		this.injectedPatientService = patientService;
	}

	private BootstrapService bootstrapService() {
		return injectedBootstrapService != null ? injectedBootstrapService
		        : Context.getService(BootstrapService.class);
	}

	private QueryStoreService queryStoreService() {
		return injectedQueryStoreService != null ? injectedQueryStoreService
		        : Context.getService(QueryStoreService.class);
	}

	/** Visible-for-testing seams: let the POJO controller test exercise the per-patient routing and
	 *  response contract without a Spring context (the real reindex round-trip is covered by
	 *  BootstrapServiceImplTest). Production resolves both services via {@link Context}. */
	void setBootstrapService(BootstrapService bootstrapService) {
		this.injectedBootstrapService = bootstrapService;
	}

	void setQueryStoreService(QueryStoreService queryStoreService) {
		this.injectedQueryStoreService = queryStoreService;
	}

	/**
	 * Maps an authorization failure to a proper status with a clean body. Without this, the framework
	 * serializes the thrown exception as an HTTP 200 carrying a full stack trace — both a misleading
	 * status and an information leak. 401 when the caller is unauthenticated; 403 when authenticated
	 * but lacking the privilege.
	 *
	 * <p>Catches both auth-failure types because they are siblings under {@code APIException}, not
	 * one hierarchy: {@link Context#requirePrivilege} (the up-front gate this controller uses) throws
	 * {@link ContextAuthenticationException}, while the {@code @Authorized} AOP throws
	 * {@link APIAuthenticationException}. The up-front gate is the only auth check that fires on the
	 * current code path, so {@link ContextAuthenticationException} is what you observe today; the
	 * {@link APIAuthenticationException} arm is defense-in-depth so an authorization failure raised by
	 * any future or downstream {@code @Authorized} call still surfaces as 401/403 rather than falling
	 * to the catch-all as a 500.
	 */
	@ExceptionHandler({ ContextAuthenticationException.class, APIAuthenticationException.class })
	@ResponseBody
	public ResponseEntity<Object> handleAuthFailure(APIException e) {
		HttpStatus status = Context.isAuthenticated() ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
		return errorResponse(status,
		        status == HttpStatus.FORBIDDEN ? "Insufficient privileges" : "Authentication required");
	}

	/**
	 * A malformed JSON request body is a client error — return 400, not the catch-all's 500. (The
	 * framework throws this during {@code @RequestBody} binding, before the handler method runs.)
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseBody
	public ResponseEntity<Object> handleMalformedBody(HttpMessageNotReadableException e) {
		return errorResponse(HttpStatus.BAD_REQUEST, "Malformed request body");
	}

	/**
	 * Catch-all so any other unexpected error returns a clean 500 rather than the framework's default
	 * for a thrown exception on this controller (an HTTP 200 carrying a serialized stack trace). The
	 * detail is logged server-side, never returned to the caller.
	 */
	@ExceptionHandler(Exception.class)
	@ResponseBody
	public ResponseEntity<Object> handleUnexpected(Exception e) {
		SafeLog.error(QueryStoreRestController.class, "Unexpected error handling a querystore REST request", e);
		return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
	}

	private static ResponseEntity<Object> errorResponse(HttpStatus status, String message) {
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("error", message);
		return new ResponseEntity<Object>(body, status);
	}
}
