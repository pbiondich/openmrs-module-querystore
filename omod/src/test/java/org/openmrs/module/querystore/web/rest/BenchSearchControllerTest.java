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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;
import org.mockito.InOrder;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** BENCHMARK ONLY: the tests that came with 4f1a8ea, moved with the endpoint to its own controller. */
public class BenchSearchControllerTest {

	private static final String UNKNOWN = "00000000-0000-4000-8000-000000000000";

	private static final String VOIDED = "voided-patient";

	private final BenchSearchController controller = new BenchSearchController();

	/** "patient-uuid" is a live patient core returns; the registered types are obs and condition. */
	@org.junit.Before
	public void knownPatientAndTypes() {
		org.openmrs.api.PatientService patients = mock(org.openmrs.api.PatientService.class);
		when(patients.getPatientByUuid("patient-uuid")).thenReturn(new org.openmrs.Patient());
		org.openmrs.Patient voided = new org.openmrs.Patient();
		voided.setVoided(true);
		when(patients.getPatientByUuid(VOIDED)).thenReturn(voided);
		controller.setPatientService(patients);
		org.openmrs.module.querystore.bootstrap.BootstrapService bootstrap = mock(
		    org.openmrs.module.querystore.bootstrap.BootstrapService.class);
		when(bootstrap.getResourceTypeNames()).thenReturn(Arrays.asList("obs", "condition"));
		controller.setBootstrapService(bootstrap);
	}

	@After
	public void clearContext() {
		Context.clearUserContext();
	}

	@Test
	public void benchSearch_returnsRowsInContractKeyOrderWithTimingHeader() {
		authenticate();
		// BENCHMARK ONLY: a Python client depends on the exact keys, their order, and the header.
		QueryStoreService queryStore = mock(QueryStoreService.class);
		QueryDocument dated = new QueryDocument();
		dated.setResourceType("obs");
		dated.setResourceUuid("obs-uuid");
		dated.setDate(LocalDate.of(2024, 1, 31));
		dated.setText("Weight: 70 kg");
		QueryDocument undated = new QueryDocument();
		undated.setResourceType("allergy");
		undated.setResourceUuid("allergy-uuid");
		undated.setText("Allergy: penicillin");
		when(queryStore.searchByPatient("patient-uuid", "weight", 50)).thenReturn(Arrays.asList(dated, undated));
		controller.setQueryStoreService(queryStore);

		ResponseEntity<Object> response = controller.benchSearch("patient-uuid", "weight", 50, false);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		String millis = response.getHeaders().getFirst("X-Querystore-Millis");
		assertNotNull(millis);
		assertTrue(Long.parseLong(millis) >= 0);
		List<?> rows = (List<?>) response.getBody();
		assertEquals(2, rows.size());
		Map<?, ?> first = (Map<?, ?>) rows.get(0);
		assertEquals(Arrays.asList("resourceType", "resourceUuid", "date", "text"), new ArrayList<Object>(first.keySet()));
		assertEquals("obs", first.get("resourceType"));
		assertEquals("obs-uuid", first.get("resourceUuid"));
		assertEquals("2024-01-31", first.get("date"));
		assertEquals("Weight: 70 kg", first.get("text"));
		Map<?, ?> second = (Map<?, ?>) rows.get(1);
		assertTrue(second.containsKey("date"));
		assertNull(second.get("date"));
	}

	@Test
	public void benchSearch_warm_neverDeletes() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		when(queryStore.searchByPatient("patient-uuid", "weight", 50)).thenReturn(Collections.<QueryDocument> emptyList());
		controller.setQueryStoreService(queryStore);

		controller.benchSearch("patient-uuid", "weight", 50, false);

		verify(queryStore, never()).bulkDeleteByPatient(anyString());
		verify(queryStore, never()).reindex(anyString());
		verify(queryStore).searchByPatient("patient-uuid", "weight", 50);
	}

	@Test
	public void benchSearch_cold_reprojectsThroughTheLockedBoundedReindex_beforeSearching() {
		// Not a bare delete from the request thread: reindex deletes and re-projects under the
		// per-patient lock inside a projection slot.
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		when(queryStore.reindex("patient-uuid")).thenReturn(org.openmrs.module.querystore.api.IndexStatus.INDEXED);
		when(queryStore.searchByPatient("patient-uuid", "weight", 50)).thenReturn(Collections.<QueryDocument> emptyList());
		controller.setQueryStoreService(queryStore);

		ResponseEntity<Object> response = controller.benchSearch("patient-uuid", "weight", 50, true);

		InOrder order = inOrder(queryStore);
		order.verify(queryStore).reindex("patient-uuid");
		order.verify(queryStore).searchByPatient("patient-uuid", "weight", 50);
		verify(queryStore, never()).bulkDeleteByPatient(anyString());
		assertEquals("indexed", response.getHeaders().getFirst("X-Querystore-Reindex"));
	}

	@Test
	public void benchSearch_cold_withNoProjectionSlotFree_is503_andSearchesNothing() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		when(queryStore.reindex("patient-uuid")).thenReturn(org.openmrs.module.querystore.api.IndexStatus.NOT_INDEXED);
		controller.setQueryStoreService(queryStore);

		ResponseEntity<Object> response = controller.benchSearch("patient-uuid", "weight", 50, true);

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		assertNotNull(response.getHeaders().getFirst("Retry-After"));
		verify(queryStore, never()).searchByPatient(anyString(), anyString(), anyInt());
	}

	@Test
	public void benchSearch_anUnknownOrVoidedPatient_is404_beforeAnyIndexCall() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);

		for (String patient : new String[] { UNKNOWN, VOIDED, "../etc/passwd" }) {
			for (boolean cold : new boolean[] { false, true }) {
				try {
					controller.benchSearch(patient, "weight", 50, cold);
					fail("expected a 404 for " + patient);
				}
				catch (PatientNotFoundException e) {
					assertEquals(HttpStatus.NOT_FOUND, controller.handlePatientNotFound(e).getStatusCode());
				}
			}
		}
		verifyNoInteractions(queryStore);
	}

	@Test
	public void benchSearch_anOverlongQuery_is400() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);
		StringBuilder q = new StringBuilder();
		for (int i = 0; i <= PatientRequests.MAX_QUERY_LENGTH; i++) {
			q.append('a');
		}

		try {
			controller.benchSearch("patient-uuid", q.toString(), 50, false);
			fail("expected a 400");
		}
		catch (BadParameterException e) {
			assertEquals(HttpStatus.BAD_REQUEST, controller.handleBadParameter(e).getStatusCode());
		}
		verifyNoInteractions(queryStore);
	}

	@Test
	public void benchSearch_aCallerWhoCannotReadEveryType_isSearchedOnlyOverTheReadableOnes() {
		// Get Patients and Get Conditions, not Get Observations: never the unfiltered searchByPatient.
		Context.setUserContext(new UserContext(null) {

			@Override
			public User getAuthenticatedUser() {
				return new User();
			}

			@Override
			public boolean hasPrivilege(String privilege) {
				return PrivilegeConstants.GET_PATIENTS.equals(privilege) || PrivilegeConstants.GET_CONDITIONS.equals(privilege);
			}
		});
		QueryStoreService queryStore = mock(QueryStoreService.class);
		when(queryStore.query(org.mockito.ArgumentMatchers.any(org.openmrs.module.querystore.api.QuerySpec.class)))
		        .thenReturn(org.openmrs.module.querystore.api.QueryResult.empty(org.openmrs.module.querystore.api.IndexStatus.INDEXED,
		            org.openmrs.module.querystore.api.QuerySpec.forPatient("p").query("x").build()));
		controller.setQueryStoreService(queryStore);

		controller.benchSearch("patient-uuid", "hiv", 50, false);

		org.mockito.ArgumentCaptor<org.openmrs.module.querystore.api.QuerySpec> spec = org.mockito.ArgumentCaptor
		        .forClass(org.openmrs.module.querystore.api.QuerySpec.class);
		verify(queryStore).query(spec.capture());
		assertEquals(Collections.singletonList("condition"), spec.getValue().getTypes());
		verify(queryStore, never()).searchByPatient(anyString(), anyString(), anyInt());
	}

	@Test
	public void benchSearch_returns400_whenQueryBlank() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);

		try {
			controller.benchSearch("patient-uuid", "   ", 50, true);
			fail("expected a 400");
		}
		catch (BadParameterException e) {
			ResponseEntity<Object> response = controller.handleBadParameter(e);
			assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
			assertEquals("q is required", errorOf(response));
		}
		verifyNoInteractions(queryStore);
	}

	@Test
	public void benchSearch_returns400_whenPatientBlank() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);

		ResponseEntity<Object> response = controller.benchSearch(null, "weight", 50, true);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		verifyNoInteractions(queryStore);
	}

	@Test
	public void benchSearch_clampsLimitTo1Through200() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		when(queryStore.searchByPatient(anyString(), anyString(), anyInt())).thenReturn(Collections.<QueryDocument> emptyList());
		controller.setQueryStoreService(queryStore);

		controller.benchSearch("patient-uuid", "weight", 0, false);
		controller.benchSearch("patient-uuid", "weight", -5, false);
		controller.benchSearch("patient-uuid", "weight", 1000, false);

		verify(queryStore, times(2)).searchByPatient("patient-uuid", "weight", 1);
		verify(queryStore).searchByPatient("patient-uuid", "weight", 200);
	}

	@Test
	public void benchSearch_withoutGetPatients_isRejectedBeforeAnyDelete() {
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return false;
			}
		});
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);
		try {
			controller.benchSearch("patient-uuid", "weight", 50, true);
			fail("expected ContextAuthenticationException");
		}
		catch (ContextAuthenticationException expected) {
			verifyNoInteractions(queryStore);
		}
	}

	@Test
	public void benchSearch_cold_withoutManageGlobalProperties_isRejectedBeforeDelete() {
		// Holds Get Patients but not Manage Global Properties: a warm search is fine, a cold one is not.
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return PrivilegeConstants.GET_PATIENTS.equals(privilege);
			}
		});
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);
		try {
			controller.benchSearch("patient-uuid", "weight", 50, true);
			fail("expected ContextAuthenticationException");
		}
		catch (ContextAuthenticationException expected) {
			verifyNoInteractions(queryStore);
		}
	}

	@Test
	public void benchSearch_mapsAuthFailuresAndUnexpectedErrorsToCleanStatuses() {
		Context.setUserContext(new UserContext(null));
		assertEquals(HttpStatus.UNAUTHORIZED, controller.handleAuthFailure(new ContextAuthenticationException("x")).getStatusCode());
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, controller.handleUnexpected(new RuntimeException("x")).getStatusCode());
	}

	private static void authenticate() {
		Context.setUserContext(new UserContext(null) {

			@Override
			public User getAuthenticatedUser() {
				return new User();
			}

			@Override
			public boolean isAuthenticated() {
				return true;
			}

			@Override
			public boolean hasPrivilege(String privilege) {
				return true;
			}
		});
	}

	private static String errorOf(ResponseEntity<Object> response) {
		return (String) ((Map<?, ?>) response.getBody()).get("error");
	}
}
